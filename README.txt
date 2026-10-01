# VortexMC-banish (Purpur 1.20.4)

Requirements:
- Java 17 JDK
- Apache Maven
- Purpur 1.20.4 (Paper API compatible)

Build:
1. Install Java 17 and Maven.
2. Open this folder in Windows.
3. Double-click `build.bat`.
4. The output will be `VortexMC-banish.jar` in this folder and `target/`.

Commands:
- `/ban <player> <duration|perm> [reason]` — examples: `/ban Steve 7d Griefing`, `/ban Steve perm Cheating`
- `/unban <player>`
- `/mute <player> <duration> [reason]` — example: `/mute Steve 2h Spam`
- `/mute <player>` — removes an active mute
- `/freeze <player> <duration> [reason]` — example: `/freeze Steve 10m Check`
- `/freeze <player>` — removes an active freeze

Duration format:
- `30s`, `15m`, `2h`, `7d`, or combined like `1d2h30m`
- Permanent (`perm`) is only supported for ban.
- Mute and freeze currently require the player to be online.
- Mute/freeze persist across restarts in `plugins/VortexMC-banish/punishments.yml`.

Discord:
Add your newly generated webhook URLs to `plugins/VortexMC-banish/config.yml` under `webhooks`.
Do not share webhook URLs publicly. Reset any webhook URL that was exposed.
