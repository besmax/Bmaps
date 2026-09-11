# Bmaps design system

The user-supplied Stitch Bmaps specification defines the visual system. `feature:shell/BmapsTheme.kt` owns the complete dark color palette, a derived light palette, Inter typography, and Material shapes. `AppShell` applies it around every destination and dialog while preserving the existing device/light/dark preference.

The supplied named color table takes precedence where the prose uses different shades. Primary containers use Trail Amber (#FF6B35); primary action buttons explicitly use that container with white labels as requested. Confirmation uses secondary colors and size estimates use tertiary colors. Light mode derives neutral surfaces and darker foreground accents from the supplied palette. All fixed color roles remain the supplied values.

Inter 4.1 Regular, SemiBold, and Bold are bundled as static font resources, so typography works offline on Android and iOS. Its SIL Open Font License is bundled in `feature:shell/src/commonMain/composeResources/files/inter_license.txt`. The upstream distribution is https://github.com/rsms/inter/releases/tag/v4.1.

Typography maps the supplied headline, title, body, and label styles to Material typography roles. `headlineLarge` is 26/32 sp below 600 dp and 32/40 sp on larger surfaces. `displayMedium` is the 28/32 metric display style and `displaySmall` is the 18/22 compact metric style. Tabular figures are enabled for all styles. Font sizes and line heights use sp; em letter spacing preserves the specified relative tracking.

Shapes use 8, 16, 24, 32, and 48 dp corners. Floating controls and primary buttons use full circular/stadium shapes with at least 48 dp touch targets. Screen margins are 16 dp below 600 dp and 24 dp on expanded layouts. Non-map content is centered within a 720 dp width; floating map panels cap at 360 dp and the expanded-layout selection action docks at the bottom right.

Map chrome uses translucent surface tiers, subtle borders, and elevation shadows. The implementation does not apply a true backdrop blur: foreground blur would blur controls rather than the map behind them, and no new rendering dependency is introduced for that effect. Attribution remains visible and linked. Existing navigation destinations use vector icons in a floating rounded bar.

The area selector retains its ViewModel-owned movement and resizing behavior. Its visual treatment is a 12% amber fill, a 2 dp amber boundary, and 8 dp corner circles within 48 dp gesture targets. Instructions use a translucent rounded surface. No dummy search, GPS, recording, telemetry, offline-ready, or 3D controls are introduced for unimplemented features.

Builds, device previews, and runtime testing are left to the user at their request.
