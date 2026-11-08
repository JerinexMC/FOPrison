# FOPrison

Paper 1.21.11 plugin for a cinematic `FO` capture ability.

## Build
This repository is configured for GitHub Actions and Java 21.
- Push to `main`, or use Actions → Build FOPrison → Run workflow.
- Download the `FOPrison` artifact.
- Extract `FOPrison.jar` and upload it to `plugins/` on your Paper server.
- Restart the server.

## Use
- Operator says `FO` in chat to activate.
- Operator says `FO STOP` to restore the original blocks and release captured entities.
- Only operators can activate or release.

## Important notes
- The ability affects living entities in a 20-block spherical radius; the caster is excluded.
- Prison construction is gradual and the original block data is saved in `plugins/FOPrison/config.yml`.
- Keep regular server backups. `FO STOP` restores blocks replaced by the plugin, but does not restore blocks players manually change inside a prison.
- This initial version stores prison block data across restarts. A server restart during the *lifting animation* does not resume that animation; completed structures remain and can still be cleared with `FO STOP`.
