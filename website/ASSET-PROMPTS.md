# Image prompts for the Aegis website

ChatGPT prompts for the images in `assets/img/`. Each number matches its file (`01` is `01-card-hand.webp`).

1. Open one new ChatGPT chat and send the brand rules first.
2. Send one prompt per message. Attach no reference photos: with a photo attached, ChatGPT edits that photo instead of making a new one.
3. Ask for the shape each prompt names (square, landscape or portrait). The page crops each image with `--ratio` and `--pos` in `index.html`.
4. Check before keeping one: no stray text or logos, cards show only the chip and contactless symbol, Android phone (punch-hole, no notch), believable hands.
5. Save it under its file name, convert with `cwebp -q 82 -m 6 in.png -o assets/img/<name>.webp`, and reload.

## Brand rules (send first)

```text
I'm making images for the website of Aegis, a crypto wallet. I'll send one image request per message. Apply these rules to every image unless a request says otherwise:

- Premium, calm, photoreal. Dark studio look with lots of empty space, like high-end phone and product photography.
- Bank cards are ordinary contactless bank cards: a gold EMV chip and the small contactless wave symbol, nothing else. No bank name, no card network (Visa, Mastercard, Discover), no card number, no name.
- Phones are modern Android phones: thin even bezels, a small centred punch-hole camera, flat edges. Never an iPhone: no Dynamic Island, no notch.
- No text, words, logos or watermarks anywhere in the image.
- Colours: near-black backgrounds (#050506), graphite surfaces (#111113 to #232327), titanium silver. Use the accents only where I ask for them: mint green #7ADBA9 and ice blue #8CCBFF.
- Every image must be original. Don't reproduce any existing brand's photos, products or models.
```

## The images

### 01 · Yours alone

`01-card-hand.webp` · ask for landscape. Feature tile. A hand lowering a plain card onto a studio floor.

```text
Studio product photo. A woman's elegant hand with a natural nude manicure comes in from the top right and lowers a matte graphite contactless bank card, holding it lightly by its top edge. The card is tilted about 30 degrees, and its bottom corner just touches a seamless dark grey studio floor. The card face points at the camera: a gold EMV chip on the left and a small contactless wave symbol, nothing else. A soft spotlight pools on the floor under the card and casts a gentle shadow. The backdrop fades from charcoal to near-black toward the top left. Edge lighting catches a thin titanium sheen along the card's edge, with a faint cool rim light on the hand. Empty space on the left. Landscape, 3:2.
```

### 02 · Tap, then touch

`02-card-phone.webp` · ask for square. Feature tile. Isometric card behind a phone, with an NFC glow.

The prompt asks for a blank screen so the real app screen can be placed in afterwards; a generated screen also works if its UI copies the app exactly.

```text
Dark, minimal 3D render, isometric view from slightly above and to the left. A modern Android phone stands upright, turned about 30 degrees to the left; we see its top half, and the bottom fades into darkness. Standing just behind it, a matte black contactless bank card rises above the top of the phone; the visible part shows a gold EMV chip and nothing else. A very faint ice-blue (#8CCBFF) contactless wave glows in the gap between the card and the top of the phone, as if the card is being read over NFC. The scene is almost all black, with soft low-key light from above and subtle reflections along the phone's metal edge. The phone screen is switched off: plain glossy black glass with no interface, so the app screen can be added later. Lots of empty black space around it. Square.
```

### 03 · No seed phrase

`03-two-cards.webp` · ask for landscape. Feature tile. Your main card plus one backup card you keep at home.

```text
Editorial portrait photo. A confident young woman (any ethnicity) in a plain black short-sleeved t-shirt, seen from the waist up and leaning slightly forward, looks straight into the camera with a calm, steady expression. One forearm rests in front of her; her other hand is raised beside her face, holding two plain contactless bank cards fanned slightly apart: the front card matte black, the one behind titanium silver. Each card shows a gold EMV chip and a contactless symbol, nothing else. Behind her, a moody stormy sky of blue-grey clouds at dusk, softly out of focus. Soft frontal key light, subtle rim light, natural skin texture, fashion-magazine quality. She sits right of centre with open sky on the left. Landscape, 3:2.
```

### 04 · Already in your pocket

`04-pocket.webp` · ask for square. Feature tile. Your crypto key is the bank card already in your pocket.

```text
Candid lifestyle close-up in hard natural sunlight with deep shadows. A man in a black top slides a matte black contactless bank card into the front pocket of light-wash blue jeans. His hand comes in from the left, the card held between thumb and index finger, half inside the pocket. The card shows a gold EMV chip and nothing else. Strong sun from the left rakes across the denim texture and the back of his hand; the top of the frame falls into deep black shadow. It should feel like the card you already carry every day. Square.
```

### 05 · Every token, any chain

`05-coins.webp` · ask for square. Feature tile. The tokens Aegis holds and swaps through NEAR Intents.

```text
This image is the one exception to the no-logos rule: the coins carry their token logos.

3D render of glossy, translucent glass-and-acrylic crypto coins floating in a tilted, slightly twisted ring on a black background with a faint teal-black vignette. At the front of the ring, larger than the rest and softly glowing: a NEAR coin, mint green (#00EC97) with the black NEAR "N" logo. Around the ring: USDC, USDT, ETH, BTC, SOL, wrapped BTC and Aurora coins with their logos, plus a few plain coins in graphite and silver with no logo. A thin mint-green light arc passes through the ring, suggesting swaps across chains. Crisp studio reflections and refraction, high detail. Square.
```

### 06 · More than a wallet

`06-phone-top.webp` · ask for landscape. Feature tile. The top of the phone, front-on, on a navy glow.

The prompt asks for a blank screen so the real app screen can be placed in afterwards; a generated screen also works if its UI copies the app exactly.

```text
Clean product shot, front-on and perfectly centred. The top half of a modern Android phone (thin even bezels, small centred punch-hole camera, slim black frame, side buttons on the right edge) rises from the bottom of the frame; the frame cuts the phone off around its middle. The phone takes up the middle third of the width, and its top edge sits about a third of the way down. The screen is switched off: plain glossy black glass with no interface, so the app screen can be added later. Background: a deep blue-black radial glow, brightest just behind the phone, fading to near-black in the corners. Symmetrical and minimal. Landscape, 3:2.
```

### 07 · Two keys, both required

`07-two-keys.webp` · ask for landscape. Security section. Card chip and phone chip connect to light the lock.

```text
Abstract 3D artwork on a pure black background, rendered in glossy liquid chrome and clear glass, perfectly symmetrical. On the left, a large gold bank-card EMV chip with its contact-pad pattern. On the right, a chrome phone security chip with a fine fingerprint pattern etched into its surface. Smooth bundles of glass-and-chrome cables flow from each chip toward the centre and plug into a glass shield with a lock in it. The centre glows soft mint green (#7ADBA9), lit because both chips are connected; everything else stays monochrome chrome. Keep everything inside a horizontal band across the middle, with plain black above and below, because it will be cropped into a very wide banner. No text. Landscape, 3:2.
```

### 08 · The app

`08-hand-phone.webp` · ask for landscape. App section. A hand holds the phone with the card tapped behind it.

The prompt asks for a blank screen so the real app screen can be placed in afterwards; a generated screen also works if its UI copies the app exactly.

```text
Product photo on a black background with a soft vignette. A hand holds a modern Android phone upright, front-on and centred, fingers wrapped around its sides. A matte black contactless bank card is pressed flat against the back of the phone, with its top third rising above the phone's top edge, the way you tap a card to pay; the visible part shows the gold EMV chip. A subtle ice-blue (#8CCBFF) contactless ripple glows where the card meets the phone. The screen is switched off: plain glossy black glass with no interface, so the app screen can be added later. Soft top light, calm and premium, empty space on both sides. Landscape, 3:2.
```

### 09 · Hero

`09-hero.webp` · ask for landscape. The first image on the page, also a promo-video end frame.

The prompt asks for a blank screen so the real app screen can be placed in afterwards; a generated screen also works if its UI copies the app exactly.

```text
Minimal wide product composition on a pure black background. A modern Android phone floats front-on, slightly left of centre, straight and upright. To its right, two plain contactless bank cards float in a loose stack with depth: the front card matte black, the second titanium silver, a little higher and further back, slightly softer in focus. Both show a gold chip and a contactless symbol, nothing else. Soft studio light and gentle reflections. Leave generous empty black space across the top for a headline. The phone screen is switched off: plain glossy black glass with no interface, so the app screen can be added later. No text. Landscape, 3:2.
```

### 10 · Ready for the day something goes wrong

`10-portrait.webp` · ask for portrait. Recovery section. Only you can move your money: your card plus your fingerprint.

```text
Editorial studio portrait on a pure black background. A young woman (any ethnicity) in side profile, facing right, wears a fitted light-blue long-sleeved top (the only colour in the frame) and one sculptural white statement earring. Near her chin she holds a matte black contactless bank card, as if about to tap it; the card shows only a gold chip. Lower in the frame, her other hand holds a modern Android phone whose screen shows a soft ice-blue (#8CCBFF) fingerprint-unlock glow. Dramatic rim light on her skin, sharp focus on her face and the card, fashion-magazine quality. Portrait, 2:3.
```

## Extras (no slot on the page yet)

### i1 · Freeze icon

`icon-freeze.png` · ask for square. Send in the same chat, in this order, so the three icons match.

```text
3D icon on a pure black background, centred, glossy liquid chrome with clear glass accents and soft studio reflections. The icon is a snowflake with a faint ice-blue (#8CCBFF) glow inside the glass. No text. Square.
```

### i2 · Delay icon

`icon-delay.png` · ask for square. Same style as the freeze icon.

```text
Same style as the last icon: glossy liquid chrome and clear glass on pure black, centred, soft studio reflections. This one is a shield with a clock face set into it. No text. Square.
```

### i3 · Inheritance icon

`icon-inheritance.png` · ask for square. Same style as the freeze icon.

```text
Same style as the last two icons: glossy liquid chrome and clear glass on pure black, centred, soft studio reflections. This one is a key passing from one open hand to another. No text. Square.
```

## The logo

Don't generate it. The mark is a vector already: two hands reaching toward a spark, `assets/aegis-mark.svg` (cream `#EFE7DE` on transparent) and `assets/logo-icon.svg` (on a near-black tile). Export PNGs from those files.
