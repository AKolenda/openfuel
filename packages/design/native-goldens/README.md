# No native visual baseline has been approved

This folder intentionally contains **no** Android/iOS golden screenshots. Do not relabel browser
images as native evidence. The manual native-review workflow captures actual app screens; it has
not run. Review those captures against `../goldens/html` and `docs/NATIVE_PARITY.md` first.

After review, add `ios/` or `android/` with `list.png`, `cards.png`, `wide.png`, `sort.png`,
`settings.png`, `about.png`, `detail.png`, `report.png` and an `approval.json` containing:

```json
{"status":"human-reviewed","device":"ACTUAL_DEVICE","os":"ACTUAL_OS","locale":"en_CA","reviewed_by":"ACTUAL_REVIEWER"}
```

Normalize filenames, not screenshots. Keep identical device/OS/text-size/status-bar configuration.
Do not resize/crop a screenshot to conceal a layout regression. A human-approved, documented crop
of system chrome can be used consistently on both reference and candidate before checking.

`tools/visual_gate.py` exits with an error for absent approvals or screenshots. It never approves
its own outputs or claims iOS and Android should have pixel-identical OS font rasterization.
The initial 1% changed-pixel tolerance with per-channel threshold 16 is a reviewable test parameter,
not proof of accessibility or a universal design-parity standard.
