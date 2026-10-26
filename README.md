# WebOptimizer (Fabric 1.21.11)

Client mod. Right Shift opens/closes the HUD.

HUD: ON/OFF, smoothness 0-1000, "Повертати меч", drag it by the title bar.
Config: `.minecraft/config/weboptimizer.json`

- Silent rotation: your own camera never moves, only the rotation sent to the server.
- Prediction: movement history + physics simulation (jump/fall/landing, walls, steps).
- No web: in water/lava, when the enemy is already in/near a web, 0.2 s after your own hit, while your shield is up.
- Sprint: when you sprint, the mod stops you, places the web, then you run again.

Build: push to GitHub -> Actions -> artifact `WebOptimizer` (jar).
