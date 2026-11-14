# Aegis website

A static marketing site: `index.html`, `styles.css`, `main.js`. No build step.

```bash
cd website && python3 -m http.server 8765   # then open http://127.0.0.1:8765
```

## Asset slots

Put each image at the path below; the page picks it up on reload. A missing
file shows a labelled placeholder instead. Numbers match the ChatGPT prompts.

| File | Section | Size (ratio) |
|---|---|---|
| `assets/img/09-hero.webp` | Hero | 4000×2400 (5:3) |
| `assets/img/hero.mp4` | Hero video over 09 (optional, muted loop) | 5:3 |
| `assets/img/01-card-hand.webp` | Yours alone | 1416×1057 |
| `assets/img/02-card-phone.webp` | Tap, then touch | 1714×1752 |
| `assets/img/04-pocket.webp` | Already in your pocket | 1050×1160 |
| `assets/img/03-two-cards.webp` | No seed phrase | 2000×1493 |
| `assets/img/05-coins.webp` | Every token, any chain | 1920×1920 |
| `assets/img/06-phone-top.webp` | More than a wallet | 1782×1160 |
| `assets/img/07-two-keys.webp` | Security art | 2880×1100 |
| `assets/img/10-portrait.webp` | Ready for the day something goes wrong | 1438×2166 |
| `assets/img/08-hand-phone.webp` | The app | 2880×1558 |
| `assets/app/home.png` | App screens: Home screenshot | 540×1050 |

Feature tiles crop their images to fill the tile (`object-fit: cover`), so
keep the subject near the centre.

`assets/app/markets.png`, `swap.png`, `coin.png` are copies of `docs/assets/`.
The logo is `assets/aegis-mark.svg` (the app's mark, cream on transparent).

The "Download for Android" button points at the GitHub repository until there
is a release APK (`TODO` in `index.html`).
