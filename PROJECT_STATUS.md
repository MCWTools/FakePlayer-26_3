# FakePlayer 26.3 - project status (2026-11-19)

Server-side fake players. MC 26.3, Fabric Loader 0.19.5, Fabric API 0.162.0+26.3, Loom 1.18, modid `fakeplayer`, package `khoa.fakeplayer`, jar `build/libs/fakeplayer-1.0.0.jar`.

## Verified
- `gradlew.bat build --offline`: BUILD SUCCESSFUL.
- `gradlew.bat runClientGameTest --offline` (real client, simulated chat/mouse, screenshots in build/run/clientGameTest/screenshots): 46 checks OK, 0 failed:
  typed spawn, player list, physics, walk/sprint/look/lookat/stopmoving/jump/sneak, hotbarslot/swaphand/swapslot, throw N/stack/all,
  leftmouse mine + attack, rightmouse place, invsee layout + real mouse clicks (inventory, main-hand cell, armor, follows hotbarslot),
  goto around wall + lava + 1-block ledge (no lava, no damage), unreachable goal, disconnect/reconnect keeps inventory+armor,
  playermodel keeps inventory, BotBrain API auto-hit.
- KCoder (`khoascript` 1.1.0) builds with `implement khoa.fakeplayer` (reflection bridge `ModApiBridge`, `distance(a, b, nearest)`).

## NOT verified
- Skin / alex model rendering: the profile property is set (unsigned textures), but the test client rendered the default skin (no network in the test). Check in the real game.
- KCoder script `fakeplayer = implement khoa.fakeplayer` was compiled but not run in game.
- middlemouse (pick block) in survival only logged, not asserted.
