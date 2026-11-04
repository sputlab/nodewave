# NodeWave website

Static presentation site (plain HTML and CSS, no build step), published with GitHub Pages by
`.github/workflows/pages.yml` whenever something under `website/` changes on `main`.

- `index.html`, `style.css`: the page
- `assets/`: images (the app icon and the screenshots in `assets/screens/`)
- `CNAME`: the custom domain, `nodewave.org` (GitHub Pages reads it from here)
- `.nojekyll`: serve the files as they are

To see it locally: `python3 -m http.server -d website` and open http://localhost:8000.

One-time setup on GitHub: *Settings -> Pages -> Build and deployment -> Source: GitHub Actions*.

DNS for the custom domain: an `A` record set pointing `nodewave.org` to GitHub Pages (185.199.108.153, 185.199.109.153, 185.199.110.153, 185.199.111.153) and a `CNAME` record `www` pointing to `sputlab.github.io`; then tick *Enforce HTTPS* in Settings -> Pages.
