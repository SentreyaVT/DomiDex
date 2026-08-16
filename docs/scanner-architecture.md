# Scanner architecture boundary

The current scanner uses CameraX for a real lifecycle-aware preview, local test captures, and lightweight frame analysis. It measures luminance/exposure, clipped highlights as a glare signal, a luma-detail sharpness proxy, and frame-to-frame motion stability. No frame or photo is uploaded.

Capture quality and recognition confidence remain separate concepts:

- Red: a measured issue such as darkness, glare, motion, or insufficient sharpness blocks readiness.
- Yellow: measured basics are acceptable, but card coverage, perspective, and all four edges/corners have not yet been verified.
- Green: reserved for a later detector that has verified every required capture-quality dimension for a stable interval. The current implementation never shows green and never auto-captures.
- Recognition confidence will later describe how certain the identity/variant model is; it must never turn a poor-quality frame green.

The future continuous workflow should wait for stable green quality, auto-capture, identify the card, show only plausible language/finish/printing variants, preselect only when confidence supports it, confirm with one tap, increment repeated copies, provide haptic/audio feedback and immediate undo, then continue without leaving the scanner. Holo and reverse-holo always remain manually overridable.

Guided grading capture is a separate mode tied to a collection entry. It will request front, back, and angled/lighted views and store photo references and analysis history. It must not reuse scanner quality as a claimed PSA estimate.
