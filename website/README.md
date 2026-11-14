# Aegis website

A static marketing site: `index.html`, `styles.css`, `main.js`. No build step.

```bash
cd website && python3 -m http.server 8765   # then open http://127.0.0.1:8765
```

## Images

The page images are generated with ChatGPT from the prompts in
`ASSET-PROMPTS.md` (numbers match), then converted to WebP at quality 82. To
replace one, overwrite the file and keep its name; a missing file shows a
labelled placeholder instead.

| File | Section | Size |
|---|---|---|
| `assets/img/09-hero.webp` | Hero | 1536×1024 |
| `assets/img/hero.mp4` | Hero video over 09 (optional, muted loop, not made yet) | 3:2 |
| `assets/img/01-card-hand.webp` | Yours alone | 1536×1024 |
| `assets/img/02-card-phone.webp` | Tap, then touch | 1116×1409 |
| `assets/img/04-pocket.webp` | Already in your pocket | 1254×1254 |
| `assets/img/03-two-cards.webp` | No seed phrase | 1536×1024 |
| `assets/img/05-coins.webp` | Every token, any chain | 1254×1254 |
| `assets/img/06-phone-top.webp` | More than a wallet | 1536×1024 |
| `assets/img/07-two-keys.webp` | Security art (cropped to the middle band) | 1536×587 |
| `assets/img/10-portrait.webp` | Ready for the day something goes wrong | 1024×1536 |
| `assets/img/08-hand-phone.webp` | The app | 1536×1024 |
| `assets/app/home.png` | App screens: Home | 540×1050 |

Each slot in `index.html` sets its shape with `--ratio` and, where the crop
needs it, its focus point with `--pos` (an `object-position`, default
`50% 50%`). Feature tiles fill their tile (`object-fit: cover`): fixed height on
desktop, 4:3 on phones.

`assets/app/home.png` is the app's Home screen with the status bar cropped off.
`assets/app/markets.png`, `swap.png`, `coin.png` are copies of `docs/assets/`.
The logo is `assets/aegis-mark.svg` (the app's mark, cream on transparent).

The "Download for Android" button points at the GitHub repository until there
is a release APK (`TODO` in `index.html`).
