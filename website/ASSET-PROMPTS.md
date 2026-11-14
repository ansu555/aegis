# Asset prompts for the Aegis website

Prompts for an image model (Midjourney, Ideogram, GPT-image, Imagen, Flux), written to match the four illustrations already in the app (`welcome_*.webp`). The site uses those four today. Generate these to replace or extend them.

## House style (paste at the end of every prompt)

> 3D product render, brushed titanium and satin black materials, soft studio lighting, gentle long shadows, floating objects, solid pastel background (#9FE3C6 mint / #C9B8F5 lilac / #F7C09A peach / #A9D4F5 sky), clean minimal composition, Inter-style sans-serif UI text, no logos of real banks or card networks, no real card numbers (use "•••• 7032"), high detail, 4k

**Negative / avoid:** Visa, Mastercard or bank logos, real people's faces, cluttered backgrounds, neon cyberpunk, coins with Bitcoin or Ethereum logos, misspelled UI text.

## 1. Hero loop (video, 5 to 8 s, 1:1 or 4:5)

> A brushed-titanium contactless bank card slowly rotates and taps the back of a matte-black smartphone; on contact, soft concentric NFC waves ripple out and a small black glass panel appears reading "Approved · 25 NEAR"; a silver coin with a stylised N spins beside it; seamless loop, camera static, mint background (#9FE3C6). + house style

Use it in place of `assets/welcome_card.webp` in `.hero-art` (a `<video autoplay muted loop playsinline>` with the current image as `poster`).

## 2. Social share image (1200×630)

> Wide banner, left half empty dark space (#050506) for headline text, right half a titanium contactless card leaning against a black smartphone showing a fingerprint icon, soft purple (#6E5BB0) and rose (#B06E8C) glow behind them. + house style

Then set the words "Aegis: your bank card is your crypto key." on top in a design tool, export as `assets/og.png`, and point `og:image` at it.

## 3. "How it works": three steps (1:1 each, same camera)

1. > Hand-free shot: a titanium contactless card hovering just above the back of a black phone, NFC wave icon between them, lilac background. + house style
2. > A black phone face up, screen shows a large fingerprint icon and "Approve 25 NEAR to alice.near", a glowing thumbprint ring around it, lilac background. + house style
3. > A floating black glass panel with two green check marks labelled "Card" and "Phone" and a small chain-link icon, a silver shield with a check mark beside it, lilac background. + house style

## 4. Extra feature illustrations (1:1, 780×780)

- **Recovery:**
  > Two black phones side by side, the old one greyed out with a small cross, the new one lit, with a dotted line to a titanium card between them; black UI panel: "Recovering · 2 of 3 approvals". Peach background. + house style
- **Guardian:**
  > A black phone with a small person-with-shield badge, a black UI panel reading "Guardian · Sam's phone · ready", with a green check. Mint background. + house style
- **Inheritance:**
  > A silver hourglass beside a black UI panel reading "Heir · alex.near · releases after 1 year of silence", with a soft envelope icon. Sky background. + house style
- **Watchtower alerts:**
  > A silver bell with a small notification dot, a black UI notification card reading "Payment waiting · 25 NEAR · Cancel". Lilac background. + house style
- **Swap:**
  > Two silver coins (one with a stylised N, one plain with a $ sign) exchanging places along curved arrows, a black UI panel "Swap 25 → 83.01 USDC". Peach background. + house style

## 5. Lifestyle photos (photo, 3:2)

> Photorealistic close-up of a hand holding a generic matte-grey contactless card against the back of an Android phone on a café table, shallow depth of field, warm natural light, no visible logos or faces, editorial style

> Photorealistic: an Android phone on a desk showing a fingerprint prompt, a plain titanium card beside it, morning light, minimal Scandinavian desk, no logos

## 6. Logo (done)

The logo is final: two hands reaching for a spark. Vector files: `assets/logo-mark.svg` (the mark alone) and `assets/logo-icon.svg` (the mark on a near-black tile, also the favicon). In the app it is `res/drawable/aegis_mark.xml` and the launcher icon layers. Keep any new artwork in its colours: cream #EFE7DE on near-black #060608.

## 7. Background texture (optional, 2560×1440)

> Abstract dark background, near-black (#050506), two very soft out-of-focus glows (purple #6E5BB0 top-right, rose #B06E8C bottom-right), faint film grain, no shapes, no text

## Output checklist

- Save as WebP (quality 80 to 85), 780 px square for feature art, under 80 KB where you can.
- Keep the same card number "•••• 7032" and the same NEAR coin across every image, so the set looks like one product.
- After replacing an image, update its `alt` text in `index.html`.
