# Discord Release Announcement Conventions

Release announcements are part of the operational history and must not be overwritten by later
releases.

1. Use one file per release tag:
   `discord-announcement-<network>-<version>.md`.
2. Keep the version-line index, such as `discord-announcement-integrationnet-v4.1.md`, as links and
   status only.
3. For new announcements, put each standalone Discord message in its own `text` fence. Keep every
   fenced block below 2,000 characters; target 1,800 or fewer when practical. Historical files may
   retain the structure used when they were written.
4. Separate advance notice, restart confirmation, and ordinal-activation confirmation because they
   are posted at different times.
5. Mark drafts and historical announcements explicitly. Once an announcement is posted, preserve its
   content. Record corrections or superseding values in a later release-specific file.
6. State the exact release tag, network, restart date, hard-fork status, activation ordinal when
   applicable, and operator action. Distinguish Global Snapshot ordinals from Currency Snapshot
   ordinals.
