# Design language

Wallpaper, readable controls and predictable remote navigation define Banana TV.
Use depth and translucency to separate controls from content. Keep the home screen sparse.

## Home

- Show weather, time and one Control Center button. Avoid a solid status-bar container.
- Choose dark or light header text from the wallpaper behind that area. Keep weather glyphs and text in the same color.
- Put inputs, network, screen saver and settings in Control Center. Keep the Menu key as a direct settings shortcut.
- From an app tile, Up focuses Control Center. From the header, Down returns to the previous app.
- Center the dock for the number of available apps. Keep app names accessible without visible captions.

## Surfaces and type

| Element | Treatment |
| --- | --- |
| Header text on a bright wallpaper | `#18212B` |
| Header text on a dark wallpaper | `#F8FAFC` |
| Unfocused Control Center button | White at 15% opacity, with a soft white edge |
| Focused control | Near-white surface with dark text or glyph |
| Panels | Dark, wallpaper-tinted material; 24 dp corners |
| App tiles | 14 dp corners; focus scales to 1.08 |
| Settings | 26 sp heading, 16 sp rows, 14 sp values; 44 dp rows |
| Clock | 16 sp medium-weight system sans |

These Android sizes target a 960 × 540 dp TV layout. Apple point sizes are references,
not values to copy directly into Android resources.

## Motion and cost

- Animate the object receiving an action; preserve its layout bounds.
- Cancel previous artwork animations when focus changes quickly.
- Preserve focus when returning from a submenu. Back dismisses the active panel.
- Use the existing cached wallpaper material. Do not add per-frame full-screen blur or optical distortion.
- Respect the existing motion preference. A 60 Hz request is not evidence of 60 fps.

## References

- [Apple: Materials](https://developer.apple.com/design/human-interface-guidelines/materials)
- [Apple: Focus and selection](https://developer.apple.com/design/human-interface-guidelines/focus-and-selection/)
- [Community tvOS design guidelines](https://github.com/ehmo/platform-design-skills/blob/main/skills/tvos/SKILL.md)

The community skill informs viewing distance, focus and spatial continuity. Android
keeps its own Back, Menu, accessibility and platform integration behavior.
