# Agent instructions (SneakyMail)

Paper plugin in Team-Sneakymouse / Lords of Minecraft. Follow the shared rules in EmptyPlugin's `AGENTS.md` and the team development policy.

## Repo-specific rules

- Persistence runs on the `MailRuntime` executor through the manager APIs; listeners do not call PocketBase directly.
- Delivery is fenced and idempotent (`MailDeliveryWorker`). Read `docs/durable-command-mail.md` and `docs/command-mail-journal.sql` before changing delivery.
- `PocketbaseListener` callbacks are async; use `runTask` before any Bukkit call.
- `PocketbaseListener` has an unhandled DELETE case (`//TODO: Properly handle deletion`); deleted mail should update open GUIs and counters.
- Reward commands (`MailReward.CommandReward`) run as console with only `{player}` substituted; never put mail text or player-supplied text into a console command.
