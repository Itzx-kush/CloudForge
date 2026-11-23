# CloudForge console

The primary audience is an engineer checking a workspace, a project or its infrastructure. Put the latest observable state first. On the overview, group the API and PostgreSQL readings from the same probe, follow them with workspace inventory and recent projects, and keep the implementation roadmap in a disclosure.

## Visual system

The palette lives in `src/styles/tokens.css`. Charcoal canvas (#11151c) and graphite panels (#191e27) support cool white text. Daylight uses a paper-gray canvas (#f3f5f7), white panels and ink (#172332). Electric blue identifies actions. Four alternate accent pairs retain distinct day and night values. Status colors belong to health and lifecycle states independently of the selected accent.

Use the local Segoe UI/system sans stack for headings and prose, and the local Cascadia Code/monospace stack for technical identifiers. Large headings use tight tracking. Values use tabular numerals. Keep body text at 14px, supporting copy around 13px and short metadata labels at 11–12px. System fonts avoid an external font request at startup.

Use 4, 8, 12, 16, 20 and 28px spacing increments. Panel spacing, row height and navigation density are tokenized. Controls use 6px corners, nested surfaces 10px and panels 12px. Favor one boundary around a group. Use a shallow shadow for resting surfaces.

The exploded three-layer illustration identifies the control plane. It is decorative, hidden from assistive technology and independent of telemetry. Display actual observations next to it. The same geometry introduces the sign-in screen.

The desktop rail and mobile drawer meet at 960px. Below 660px the overview reflows and the header keeps primary controls in reach. Dense tables own their horizontal scrolling. Motion uses the short and standard timing tokens (150 and 220ms). Both user-selected and operating-system reduced motion suppress decorative animation.
