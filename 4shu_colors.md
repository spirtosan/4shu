# 4shu Color Schema — Dark Theme (forced, no light mode)

## Core Colors
| Role | Hex | Usage |
|------|-----|-------|
| Background | `#141928` | Main screens, activity backgrounds |
| Surface | `#1A2035` | Toolbar, cards, dialogs, bottom sheets |
| Surface elevated | `#1E2535` | Received bubbles, sheet backgrounds |
| Sent bubble | `#1E3A5F` | Sent message bubbles |
| Received bubble | `#1E2535` | Received message bubbles |
| Accent / Primary | `#E8711A` | Buttons, links, highlights, star icon |
| Accent pressed | `#C45E14` | Pressed state for accent elements |
| Text primary | `#FFFFFF` | Main text |
| Text secondary | `#9090A0` | Subtitles, timestamps, hints |
| Text tertiary | `#6A6A7A` | Placeholders, disabled text |
| Online dot | `#4CAF50` | Online status indicator |
| Danger / Delete | `#E53935` | Destructive actions, block button |
| Border / Divider | `#252D45` | Subtle separators |

## Avatar Colors (letter fallback, index = username.hashCode().absoluteValue % 10)
| Index | Hex | Color name |
|-------|-----|------------|
| avatar_1 | `#1565C0` | Deep blue |
| avatar_2 | `#6A1B9A` | Purple |
| avatar_3 | `#AD1457` | Pink |
| avatar_4 | `#00695C` | Teal |
| avatar_5 | `#E65100` | Deep orange |
| avatar_6 | `#37474F` | Blue grey |
| avatar_7 | `#558B2F` | Olive green |
| avatar_8 | `#4527A0` | Deep purple |
| avatar_9 | `#00838F` | Cyan |
| avatar_10 | `#C62828` | Deep red |

## Typography
- Primary font: system default (Roboto on Android)
- Message bubbles: 15sp
- Timestamps: 11sp
- Section headers: 12sp all-caps
- Secondary text: 13sp

## Corner Radius
- Message bubbles: 12dp
- Buttons: 8dp
- Dialogs / bottom sheets: 16dp top corners
- Avatar circles: 50% (full circle)

## Design Rules
- Dark theme only — no light mode ever
- Orange (`#E8711A`) is the ONLY accent color — used sparingly for interactive elements
- No gradients — flat colors only
- Subtle borders (`#252D45`) instead of shadows
- Avatar size in contact list: 48dp circle
- Avatar size in chat toolbar: 36dp circle
- Avatar size in profile screens: 80dp circle
- Own avatar in main toolbar: 36dp circle
