# Web Scraping

Polite and robust scraping patterns for agents.

- Check robots.txt and Terms before scraping; set a descriptive User-Agent.
- Respect rate limits (1–2 req/s) and Retry-After; back off on 429s.
- Cache responses on disk; parse with the repo's preferred library.
- Prefer stable APIs/selectors; handle pagination and missing fields defensively.
- Never store PII longer than needed; keep scraped data in the workspace only.
