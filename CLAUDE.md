Fork of Moonlight for Android (game streaming client), used to stream a
Fedora/NVIDIA (GTX 960, X11) PC to a Samsung S24.

- Build, signing, CI, install and upstream merges: Docs/android.md
- Hotkey overlay (fork feature, layout JSON, code map): Docs/hotkeys.md
- In-app updates from GitHub releases (update.json, CI, testing): Docs/updates.md
- Pinch zoom / two-finger pan of the stream: Docs/zoom.md
- all documentation/plans/instructions for agents/research must be in /Docs,
  update it when implementing things
- keep fork-only build settings in app/fork.gradle; keep upstream files'
  diffs small so `git merge upstream/master` stays easy
- always fix the root problem, no hacky fixes; don't break anything
- don't ever put claude as coauthor/author on git
- commits must be concise but clear
- when I say push I mean add/commit/push with a concise, clear message
