"""
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║                        !! AI MODIFICATION WARNING !!                    ║
# ╠══════════════════════════════════════════════════════════════════════════╣
# ║  This file is the result of careful reverse-engineering of Samsung       ║
# ║  Galaxy's libphotoeditorEngine.so combined with physical calibration     ║
# ║  measurements from a real Galaxy device.                                 ║
# ║                                                                          ║
# ║  ALL numeric constants (weights, thresholds, LUT values, sigma values)  ║
# ║  were either directly extracted from C decompiled source or fitted       ║
# ║  against real device output. They are NOT approximations to be           ║
# ║  "improved" — changing them will break device accuracy.                  ║
# ║                                                                          ║
# ║  DO NOT:                                                                 ║
# ║    • Replace constants with "cleaner" or "standard" values               ║
# ║    • Refactor the algorithm structure (order matters in the pipeline)    ║
# ║    • Substitute standard image processing formulas for the C port        ║
# ║    • Add smoothing, clamping, or normalisation not present in C code     ║
# ║                                                                          ║
# ║  To modify this file, first read calibration/fitted_constants.json      ║
# ║  and the corresponding sections of libphotoeditorEngine.so.c.            ║
# ╚══════════════════════════════════════════════════════════════════════════╝

GalaxyPhotoEditor
=================
Python port of Samsung Galaxy libphotoeditorEngine.so

Sources:
  • Java_com_sec_android_mimage_photoretouching_jni_Engine_controlTone2021
    → mi_control_Tone2021  (lines 13731-13955)
  • Java_com_sec_android_mimage_photoretouching_jni_Engine_controlColorTuning
    → control_Color_Tuning  (lines 19723-20400+)

Parameter conventions
---------------------
  All UI values: int in [-100, 100]  (0 = no change)
  Internal C values: [0, 200],  default = 100  → internal = ui_val + 100

Calibration
-----------
  Constants fitted from real Galaxy device output (see calibration/):
    brightness_weight = 0.7999
    highlights: weight=0.00276, offset=0.4978
    shadows-:   weight_m=0.00301, threshold_m=159.4
    shadows+:   plateau_ratio=0.28, weight_p=0.0022
    exposure:   quadratic (step*|step|/100)
    temperature: 8-point measured LUT (±25/50/75/100)
    color tuning sigma: Hue=22.2°, Sat=14.3°, Lum=13.6°

Not ported (not found in .so):
  LightBalance, Sharpness, Definition
"""

import math
import numpy as np
import cv2


# ══════════════════════════════════════════════════════════════════
#  Static table construction
# ══════════════════════════════════════════════════════════════════

def _build_sin_cos_tables() -> tuple:
    """1440-entry sin/cos tables  (1440 = 360° × 4 sub-steps, matching C code)."""
    angles = np.arange(1440, dtype=np.float64) * (2.0 * math.pi / 1440.0)
    return np.sin(angles).astype(np.float32), np.cos(angles).astype(np.float32)


_SIN_TBL, _COS_TBL = _build_sin_cos_tables()


# ── White-balance LUTs ────────────────────────────────────────────

def _kelvin_to_rgb(T: float) -> tuple:
    """
    Tanner Helland's Kelvin → (R, G, B) approximation.
    Returns float values in [0, 255].
    """
    t = T / 100.0
    # Red
    r = 255.0 if t <= 66 else float(np.clip(329.698727446 * (t - 60.0) ** -0.1332047592, 0, 255))
    # Green
    if t <= 66:
        g = float(np.clip(99.4708025861 * math.log(t) - 161.1195681661, 0, 255))
    else:
        g = float(np.clip(288.1221695283 * (t - 60.0) ** -0.0755148492, 0, 255))
    # Blue
    if t >= 66:
        b = 255.0
    elif t <= 19:
        b = 0.0
    else:
        b = float(np.clip(138.5177312231 * math.log(t - 10.0) - 305.0447927307, 0, 255))
    return r, g, b


# ── Temperature: calibrated wb multipliers (8 measured points, interpolated) ──
# Measured from real Galaxy device output on calib_wb_channels.png.
# ui_val → (wb_r, wb_g, wb_b) where the minimum channel is always 1.0.
_WB_CALIB_UI  = np.array([-100, -75, -50, -25,   0,  +25,  +50,  +75, +100], dtype=np.float32)
_WB_CALIB_R   = np.array([1.0000, 1.0000, 1.0000, 1.0000, 1.0000, 1.0631, 1.1343, 1.1985, 1.2466], dtype=np.float32)
_WB_CALIB_G   = np.array([1.6718, 1.3373, 1.1709, 1.0812, 1.0000, 1.0588, 1.1000, 1.1295, 1.1603], dtype=np.float32)
_WB_CALIB_B   = np.array([4.7097, 2.0169, 1.3960, 1.1550, 1.0000, 1.0000, 1.0000, 1.0000, 1.0000], dtype=np.float32)


def _wb_multipliers(ui_val: float) -> tuple:
    """Interpolate calibrated wb multipliers for any ui_val in [-100, 100]."""
    ui_val = float(np.clip(ui_val, -100, 100))
    wb_r = float(np.interp(ui_val, _WB_CALIB_UI, _WB_CALIB_R))
    wb_g = float(np.interp(ui_val, _WB_CALIB_UI, _WB_CALIB_G))
    wb_b = float(np.interp(ui_val, _WB_CALIB_UI, _WB_CALIB_B))
    return wb_r, wb_g, wb_b


# ── Contrast LUT ─────────────────────────────────────────────────

def _build_contrast_lut(internal_val: int) -> np.ndarray:
    """
    C code (lines 14027-14057):
        fVar1 = (step + 300) / 400.0
        curve = fVar1 * fVar1
        out[i] = clamp( (0.5 + curve * (i/255 - 0.5)) * 255, 0, 255 )

    internal_val: 0-200 (default 100 → identity mapping)
    """
    fv    = (internal_val + 300.0) / 400.0
    curve = fv * fv
    i     = np.arange(256, dtype=np.float64)
    out   = (0.5 + curve * (i / 255.0 - 0.5)) * 255.0
    return np.clip(out, 0, 255).astype(np.uint8)


# ══════════════════════════════════════════════════════════════════
#  Main editor class
# ══════════════════════════════════════════════════════════════════

class GalaxyPhotoEditor:
    """
    Samsung Galaxy libphotoeditorEngine.so – Python port.

    Usage
    -----
    editor = GalaxyPhotoEditor()
    out    = editor.apply_all(rgb_img, tone_params, color_params)

    tone_params  : dict  {param_name: int [-100, 100]}
    color_params : dict  {'Red': {'hue': int, 'saturation': int, 'luminance': int}, ...}
    """

    # 7 hue-center angles (degrees) from C code DAT_0010c350
    COLOR_CENTERS = np.array([0, 40, 60, 120, 180, 240, 300], dtype=np.float32)
    COLOR_NAMES   = ['Red', 'Orange', 'Yellow', 'Green', 'Blue', 'Navy', 'Purple']
    # Gaussian σ per dimension — calibrated from real Galaxy device (Red channel):
    #   Hue: 22.2°,  Sat: 14.3°,  Lum: 13.6°
    COLOR_SIGMA_HUE = 22.2
    COLOR_SIGMA_SAT = 14.3
    COLOR_SIGMA_LUM = 13.6
    # Max shift per unit param (gain_per_step × 100):
    #   Hue: ±45°/100,  Sat: ±58%/100,  Lum: ±21%/100  (calibrated)
    COLOR_GAIN_HUE  = 0.450   # degrees per ui unit
    COLOR_GAIN_SAT  = 0.582   # fraction per ui unit  (applied to S in [0,1])
    COLOR_GAIN_LUM  = 0.212   # fraction per ui unit  (applied to L in [0,1])

    # ── Public API ───────────────────────────────────────────────

    def apply_tone(self, img: np.ndarray, params: dict) -> np.ndarray:
        """
        Apply tone corrections in C-code pipeline order:
            Temperature → Contrast → Tint → Saturation →
            [Brightness / Exposure / Highlights / Shadows  (via YCbCr)]

        img    : uint8 RGB [H, W, 3]
        params : dict  (absent keys default to 0)
        """
        def get(k): return params.get(k, 0)

        out = img.astype(np.float32)

        # 1. Temperature / Kelvin white-balance (C lines 13807-13829)
        if get('Temperature') != 0:
            out = self._apply_temperature(out, get('Temperature'))

        # 2. Contrast – LUT curve (C lines 13830-13833, 13996-14062)
        if get('Contrast') != 0:
            out = self._apply_contrast(out, get('Contrast'))

        # 3. Tint – luminance-weighted green↔magenta (C lines 13834-13841, 14220-14266)
        if get('Tint') != 0:
            out = self._apply_tint(out, get('Tint'))

        # 4. Saturation – fixed-point RGB mixing matrix (C lines 13914-13932, 14272-14303)
        if get('Saturation') != 0:
            out = self._apply_saturation(out, get('Saturation'))

        # 5-8. Brightness / Exposure / Highlights / Shadows  (YCbCr luma domain)
        b = get('Brightness'); e = get('Exposure')
        h = get('Highlights'); s = get('Shadows')
        if b or e or h or s:
            out = self._apply_luma_effects(out, b, e, h, s)

        return np.clip(out, 0, 255).astype(np.uint8)

    def apply_color_tuning(self, img: np.ndarray, color_params: dict) -> np.ndarray:
        """
        Per-hue HSL tuning across 7 color ranges (C lines 19723-20400+).

        color_params:
            {'Red': {'hue': int, 'saturation': int, 'luminance': int}, ...}
        All values [-100, 100].
        """
        h_lut = np.zeros(361, dtype=np.float32)   # hue-shift (degrees)
        s_lut = np.zeros(361, dtype=np.float32)   # sat-shift  (fraction of 1)
        l_lut = np.zeros(361, dtype=np.float32)   # lum-shift  (fraction of 1)

        sigma2_h = 2.0 * self.COLOR_SIGMA_HUE ** 2
        sigma2_s = 2.0 * self.COLOR_SIGMA_SAT ** 2
        sigma2_l = 2.0 * self.COLOR_SIGMA_LUM ** 2

        for i, name in enumerate(self.COLOR_NAMES):
            cp    = color_params.get(name, {})
            h_val = cp.get('hue',        0) * self.COLOR_GAIN_HUE   # degrees
            s_val = cp.get('saturation', 0) * self.COLOR_GAIN_SAT / 100.0  # fraction
            l_val = cp.get('luminance',  0) * self.COLOR_GAIN_LUM / 100.0  # fraction

            center = float(self.COLOR_CENTERS[i])
            for deg in range(361):
                d = ((deg - center + 180.0) % 360.0) - 180.0
                w_h = math.exp(-(d * d) / sigma2_h)
                w_s = math.exp(-(d * d) / sigma2_s)
                w_l = math.exp(-(d * d) / sigma2_l)
                h_lut[deg] += w_h * h_val
                s_lut[deg] += w_s * s_val
                l_lut[deg] += w_l * l_val

        return self._apply_color_luts(img, h_lut, s_lut, l_lut)

    def apply_all(self, img: np.ndarray,
                  params: dict,
                  color_params: dict | None = None) -> np.ndarray:
        """Tone corrections first, then per-color HSL tuning."""
        out = self.apply_tone(img, params)
        if color_params:
            out = self.apply_color_tuning(out, color_params)
        return out

    # ── Tone internals ───────────────────────────────────────────

    def _apply_temperature(self, img_f: np.ndarray, ui_val: int) -> np.ndarray:
        """
        Per-channel multiplier from calibrated 8-point LUT (interpolated).
        """
        wb_r, wb_g, wb_b = _wb_multipliers(ui_val)
        out  = img_f.copy()
        out[:, :, 0] = np.clip(img_f[:, :, 0] * wb_r, 0, 255)
        out[:, :, 1] = np.clip(img_f[:, :, 1] * wb_g, 0, 255)
        out[:, :, 2] = np.clip(img_f[:, :, 2] * wb_b, 0, 255)
        return out

    def _apply_contrast(self, img_f: np.ndarray, ui_val: int) -> np.ndarray:
        """
        Build 256-entry LUT, then apply per channel (C lines 14027-14057).
        """
        lut    = _build_contrast_lut(ui_val + 100)
        img_u8 = np.clip(img_f, 0, 255).astype(np.uint8)
        return lut[img_u8].astype(np.float32)

    def _apply_tint(self, img_f: np.ndarray, ui_val: int) -> np.ndarray:
        """
        C code (lines 14220-14266):
            L = 0.2126 R + 0.7152 G + 0.0722 B
            fVar = (L/170)^2 if L < 128, else ((255-L)/170)^2

            tint < 0  → green boost:  G += fVar * 0.0034 * |tint| * G
            tint > 0  → magenta:      R += fVar * 0.0032 * tint * R
                                      B += fVar * 0.0034 * tint * B
                        (dark pixels) G += fVar * 0.0005 * tint * G  [L < 51]
        """
        tint = float(ui_val)
        R = img_f[:, :, 0]; G = img_f[:, :, 1]; B = img_f[:, :, 2]
        L    = 0.2126 * R + 0.7152 * G + 0.0722 * B
        fVar = np.where(L < 128.0,
                        (L / 170.0) ** 2,
                        ((255.0 - L) / 170.0) ** 2)
        out = img_f.copy()
        if tint < 0:
            # negative tint → boost green
            out[:, :, 1] = np.clip(G + fVar * 0.0034 * (-tint) * G, 0, 255)
        else:
            # positive tint → boost R and B (magenta)
            out[:, :, 0] = np.clip(R + fVar * 0.0032 * tint * R, 0, 255)
            out[:, :, 2] = np.clip(B + fVar * 0.0034 * tint * B, 0, 255)
            # very dark pixels only: slight G shift
            dark = L < 51.0
            G_new = np.where(dark,
                             G + fVar * 0.0005 * tint * G,
                             G)
            out[:, :, 1] = np.clip(G_new, 0, 255)
        return out

    def _apply_saturation(self, img_f: np.ndarray, ui_val: int) -> np.ndarray:
        """
        C code fixed-point RGB mixing matrix (lines 13914-13932, 14272-14303).

            internal = ui_val + 100  (0-200)
            s32Weight = clamp(int((internal*103 - 10300)*0.1 + 1024), 0, 2048)
            s32Gain   = 1024 - s32Weight
            R_gain = (s32Gain * 316) >> 10
            G_gain = (s32Gain * 624) >> 10
            B_gain = (s32Gain *  84) >> 10

            R' = ( G*G_gain + (Weight+R_gain)*R + B*B_gain ) / 1024
            G' = ( R*R_gain + (G_gain+Weight)*G + B*B_gain ) / 1024
            B' = ( G*G_gain +  R*R_gain + (B_gain+Weight)*B ) / 1024
        """
        internal  = ui_val + 100
        s32Weight = int(np.clip((internal * 103 - 10300) * 0.1 + 1024.0, 0, 2048))
        s32Gain   = 1024 - s32Weight
        R_gain    = (s32Gain * 316) >> 10
        G_gain    = (s32Gain * 624) >> 10
        B_gain    = (s32Gain *  84) >> 10

        R = img_f[:, :, 0]; G = img_f[:, :, 1]; B = img_f[:, :, 2]
        R_new = G * G_gain + (s32Weight + R_gain) * R + B * B_gain
        G_new = R * R_gain + (G_gain + s32Weight) * G + B * B_gain
        B_new = G * G_gain + R * R_gain + (B_gain + s32Weight) * B

        out = img_f.copy()
        out[:, :, 0] = np.clip(R_new / 1024.0, 0, 255)
        out[:, :, 1] = np.clip(G_new / 1024.0, 0, 255)
        out[:, :, 2] = np.clip(B_new / 1024.0, 0, 255)
        return out

    def _apply_luma_effects(self, img_f: np.ndarray,
                             brightness: int, exposure: int,
                             highlights: int, shadows: int) -> np.ndarray:
        """
        C code (lines 14551-14610): all four effects operate on the luma (Y)
        channel while preserving Cb/Cr chroma.

        RGB → YCbCr  (BT.601 fixed-point, C lines 14552, 14591-14592)
            Y  = (77R + 150G + 29B) / 256
            Cb = (-43R - 85G + 128B) / 256
            Cr = (128R - 107G - 21B) / 256

        YCbCr → RGB  (C lines 14593-14595)
            R' = Y + Cr * 359 / 256
            G' = Y - (Cb * 88 + Cr * 183) / 256
            B' = Y + Cb * 454 / 256
        """
        R = img_f[:, :, 0]; G = img_f[:, :, 1]; B = img_f[:, :, 2]

        Y  = ( 77.0 * R + 150.0 * G +  29.0 * B) / 256.0
        Cb = (-43.0 * R -  85.0 * G + 128.0 * B) / 256.0
        Cr = (128.0 * R - 107.0 * G -  21.0 * B) / 256.0

        # ── Brightness (C lines 14554-14561) ──────────────────────
        # brightness_weight=0.7999 calibrated from real Galaxy device output
        if brightness:
            step = float(brightness)
            w    = 0.7999
            lo   = Y < 128.0
            Y = np.where(lo,
                         (w * step + 127.0) * Y / 127.0,
                         w * step * (255.0 - Y) / 127.0 + Y)
            Y = np.clip(Y, 0, 255)

        # ── Exposure / Plus (C line 14564) ────────────────────────
        # Calibrated: diff = step * |step| / 100 (quadratic, flat offset on Y)
        # step=±50 → ±25 luma,  step=±100 → ±100 luma
        if exposure:
            exp_f = float(exposure)
            Y = np.clip(Y + exp_f * abs(exp_f) / 100.0, 0, 255)

        # ── Highlights (C lines 14566-14570) ──────────────────────
        # C formula: Y' = Y + weight * hl_step * Y * (Y/threshold - offset)
        # Calibrated: weight=0.00276, threshold=200, offset=0.4978
        if highlights:
            hl   = float(highlights)
            Y_hl = Y + 0.00276 * hl * Y * (Y / 200.0 - 0.4978)
            Y    = np.clip(Y_hl, 0, 255)

        # ── Shadows (C lines 14571-14590) ─────────────────────────
        # Calibrated from real Galaxy device output:
        #   negative: weight_m=0.00301, threshold_m=159.4
        #   positive: plateau at 0.28*step for Y<=128, linear weight_p≈0.0022*(255-Y)*step for Y>128
        if shadows:
            sh = float(shadows)
            if sh < 0:
                # Darken shadows: only affects Y <= threshold_m
                mask  = Y <= 159.4
                Y_sh  = Y + 0.00301 * (159.4 - Y) * sh
                Y     = np.where(mask, np.clip(Y_sh, 0, 255), Y)
            else:
                # Brighten shadows: plateau for Y<=128, linear decay for Y>128
                Y_linear  = Y + 0.0022 * (255.0 - Y) * sh
                Y_plateau = Y + 0.28 * sh
                Y_sh = np.where(Y <= 128.0, Y_plateau, Y_linear)
                Y    = np.clip(Y_sh, 0, 255)

        # ── YCbCr → RGB ───────────────────────────────────────────
        R_new = Y + Cr * (359.0 / 256.0)
        G_new = Y - (Cb * 88.0 + Cr * 183.0) / 256.0
        B_new = Y + Cb * (454.0 / 256.0)

        out = img_f.copy()
        out[:, :, 0] = np.clip(R_new, 0, 255)
        out[:, :, 1] = np.clip(G_new, 0, 255)
        out[:, :, 2] = np.clip(B_new, 0, 255)
        return out

    # ── Color tuning internals ───────────────────────────────────

    def _apply_color_luts(self, img: np.ndarray,
                           h_lut: np.ndarray,
                           s_lut: np.ndarray,
                           l_lut: np.ndarray) -> np.ndarray:
        """
        Apply prebuilt 361-entry shift LUTs in HSL space.
        HSL chosen over HSV to match Galaxy's 'Luminance' semantics.
        """
        img_u8  = np.clip(img, 0, 255).astype(np.uint8)
        # Convert RGB → HLS (OpenCV: H in [0,180], L/S in [0,255])
        hls     = cv2.cvtColor(img_u8, cv2.COLOR_RGB2HLS).astype(np.float32)
        H = hls[:, :, 0] * 2.0          # → [0, 360)
        L = hls[:, :, 1] / 255.0        # → [0, 1]
        S = hls[:, :, 2] / 255.0        # → [0, 1]

        # Index into LUTs
        h_idx = np.clip(np.round(H).astype(np.int32), 0, 360)
        dH = h_lut[h_idx]
        dS = s_lut[h_idx]
        dL = l_lut[h_idx]

        H_new = (H + dH) % 360.0
        L_new = np.clip(L + dL, 0.0, 1.0)
        S_new = np.clip(S + dS, 0.0, 1.0)

        hls_new = np.stack([
            (H_new / 2.0).clip(0, 179),
            (L_new * 255.0).clip(0, 255),
            (S_new * 255.0).clip(0, 255),
        ], axis=-1).astype(np.uint8)

        return cv2.cvtColor(hls_new, cv2.COLOR_HLS2RGB)


# ──────────────────────────────────────────────────────────────────
#  Default parameter templates
# ──────────────────────────────────────────────────────────────────

DEFAULT_TONE_PARAMS = {
    'Brightness':   0,
    'Exposure':     0,
    'Contrast':     0,
    'Highlights':   0,
    'Shadows':      0,
    'Saturation':   0,
    'Tint':         0,
    'Temperature':  0,
}

DEFAULT_COLOR_PARAMS = {
    name: {'hue': 0, 'saturation': 0, 'luminance': 0}
    for name in GalaxyPhotoEditor.COLOR_NAMES
}
