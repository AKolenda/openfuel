# OpenFuel identity

The approved Open lane F is the active OpenFuel identity. Forest green is `#285B43`; the mark is also supplied in white within the dark wordmark and app icon. Station markers continue to use the station’s own remote brand logo.

- `openfuel-mark.svg` and `openfuel-mark.png`: transparent forest mark for headers.
- `openfuel-wordmark-light.svg` / `openfuel-wordmark-dark.svg`: outlined lettering for light / dark backgrounds.
- `openfuel-app-icon.svg` and `openfuel-app-icon-1024.png`: opaque square master; platforms apply their own corner mask.

The mark uses viewBox `0 0 104 96` and path `M24 82V40C24 24 34 14 50 14H82V30H51C43 30 40 34 40 42V47H73V63H40V82Z`. Native vector implementations use the same geometry. Web delivery copies live in `apps/web/brand`. PNGs are browser raster exports of these vectors.

Lettering is outlined from locally installed Noto Sans Display SemiBold (SIL Open Font License); no font binaries are shipped. Original icon paths are first-party work under the repository licence. The original presentation and unused alternate remain in `../logo-proposal/`.
