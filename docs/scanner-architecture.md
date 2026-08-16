# Scanner architecture boundary

The current scanner uses CameraX for a real lifecycle-aware preview, local captures, and lightweight frame analysis. It measures luminance/exposure, clipped highlights as a glare signal, a luma-detail sharpness proxy, and frame-to-frame motion stability. No frame or photo is uploaded.

Capture quality and recognition confidence remain separate concepts:

- Red: a measured issue such as darkness, glare, motion, or insufficient sharpness blocks readiness.
- Yellow: the frame is improving, but at least one required dimension is not yet stable or acceptable.
- Green: OpenCV has found a convex four-corner card-shaped contour with acceptable coverage and perspective, while light, glare, sharpness, and motion also remain acceptable for six analyzed frames. Green means capture-ready only; it does not mean identified.
- Recognition confidence will later describe how certain the identity/variant model is; it must never turn a poor-quality frame green.

Automatic capture is enabled by default and fires exactly once when quality reaches stable green. It stays disarmed while the same card remains visible and rearms only after three consecutive no-card frames. A successful capture provides haptic feedback, a system camera click, a prominent confirmation, and immediate undo. A 12-second timeout prevents a disconnected camera from leaving capture stuck forever.

After capture, bundled ML Kit v2 models read Latin, Japanese, and Chinese text locally. The parser extracts useful name lines and collector-number patterns, then queries the already-imported Cardmarket catalog for possible products. These are explicitly presented as candidates requiring confirmation. Script detection is not claimed as confirmed language, and OCR does not claim finish, printing, or exact identity. The models are bundled so first use does not depend on a model download.

The detailed quality metrics are outside the preview and collapsed by default. Only the card frame, traffic-light color, and one concise instruction remain over the live image.

The future continuous workflow will identify the captured card, show only plausible language/finish/printing variants, preselect only when confidence supports it, confirm with one tap, increment repeated copies, and then continue without leaving the scanner. Holo and reverse-holo always remain manually overridable.

Live validation on 2026-08-16 used physical cards through a Logitech C270 USB webcam connected to the Android emulator. The quality state reached green, automatic capture saved a JPEG exactly once, holding the card did not cause a duplicate, removing and presenting it again correctly rearmed capture, and the camera click was audible. This validates the end-to-end gate on that setup but does not replace broader testing across phones, natural hand motion, sleeves, backgrounds, lighting, and card finishes.

Guided grading capture is a separate mode tied to a collection entry. It will request front, back, and angled/lighted views and store photo references and analysis history. It must not reuse scanner quality as a claimed PSA estimate.
