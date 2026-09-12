# Design specifications

## Shared product

OpenFuel Canada is a working-name Canadian-focused project. Do not claim Canadian incorporation, ownership, nationwide availability, verified prices or an app-store launch. Keep “openfuel” as a wordmark, without the pump logo or shield. App controls stay compact; report age sits below price, the Go action sits alongside it by default, and the map gets priority. All custom mobile dialogs are bottom sheets. Native Android mirrors that with Material 3 bottom sheets. Desktop behavior is preserved except where the existing sort/filter sheets were already bottom-anchored.

Use genuine library icons for controls rather than brand-like invented drawings. The web app preserves its earlier Lucide and third-party notice sections. The Android source uses Material icons. The website uses text/CSS controls, not generated oil-company logos.

## Website A: The everyday route

White, forest green #245A43, pale green #F0F5EA, muted green #647366, and restrained Canadian red #C64032. Avenir Next / Trebuchet / system sans fallbacks; no downloaded font files. Large left-aligned product headline paired with the actual map-first UI screenshot. The map illustration behind the phone is original sample geography. The three-part information strip names real product properties instead of invented user counts. Lower sections explain product decisions, verified station changes, native development status and FAQs.

## Website B: The public utility

Canadian red #BD3328 carries the main hero; the rest is white, natural greens and a calm public-data section. Large left-aligned headline, a working sample fuel-price board, and a downloadable synthetic CSV put data in view. This is a distinct composition, not only a recolour of A. The hero and board carry the visual emphasis; other sections remain restrained.

## Working interactions

Both pages have EN/FR copy switching, a mobile navigation sheet, a self-contained embedded map prototype, expandable FAQs, source export and honest build-status panels. B also has a fuel-grade selector and CSV export. Source export is a local download, not a fake GitHub destination. There is no waitlist signup or invented signup success. The public repository has not been published.

The landing embeds use plain initials to avoid automatic third-party image requests. The separate `app-preview.html` preserves the existing optional remotely loaded brand logos and their notices. Its map links only send a query after explicit navigation. The HTML app preview remains English; the landing text and Android resources have French variants.

## Design reference

The requested Claude resource was located as Anthropic's official `frontend-design` skill:
https://github.com/anthropics/skills/tree/main/skills/frontend-design
https://raw.githubusercontent.com/anthropics/skills/main/skills/frontend-design/SKILL.md

It was read as design guidance. Claude was not invoked and no claim is made that these prototypes were produced by Claude. The skill file is not redistributed in this bundle.
