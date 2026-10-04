# JobPilot: Step-by-Step Build Plan

A Spring Boot service that finds jobs matching your profile, tailors your resume per job, asks you to approve on Telegram, applies (where it safely can), and runs 24/7 on a free cloud VM.

**Base package:** `io.github.vaishnav1307.jobpilot` | **Packaging:** JAR | **Java:** 21 | **Build:** Maven

---

## 1. Decisions locked in (and corrections to my earlier suggestions)

I re-checked versions and APIs before writing this. Three earlier suggestions change:

| Topic | Decision | Why |
| --- | --- | --- |
| Spring Boot version | **Latest stable 4.1.x** (4.1.0 released 10 Jun 2026). Never pick SNAPSHOT or milestone (M/RC) | Boot 3.5 open-source support ended 30 Jun 2026. 4.0.x ends 31 Dec 2026 |
| Starter names (changed) | `spring-boot-starter-webmvc` (Initializr's "Spring Web"), `spring-boot-starter-flyway` | Boot 4 is modular. Old names like `starter-web` work only via the temporary `starter-classic` bridge |
| JSON (changed) | **Jackson 3** (`tools.jackson.databind.*`). Annotations stay `com.fasterxml.jackson.annotation.*` | Boot 4 default. Most older tutorials show Jackson 2 imports |
| Retries (changed) | Use Spring Framework 7's built-in `@Retryable` / `@ConcurrencyLimit` (enabled with `@EnableResilientMethods`). **Drop Resilience4j** | One less dependency. Confirm exact names in the Spring Framework 7 reference docs |
| PDF generation (changed) | Render Thymeleaf HTML to PDF with **Playwright `page.pdf()`**. Drop OpenHTMLtoPDF and PDFBox | OpenHTMLtoPDF may pin an older PDFBox, which risks conflicts. You need Playwright anyway |
| Resume source of truth | A hand-written `resume.json`, not a parsed PDF | Parsing PDFs is fragile. JSON is exact |
| HTTP client | `RestClient` | Simple and synchronous, which suits virtual threads |
| LLM client | Your own small `LlmClient` interface with plain `RestClient` calls | Avoids coupling to Spring AI versions, and you learn more |
| Testing HTTP clients | `MockRestServiceServer` (built in) plus saved JSON fixtures | No WireMock/Jetty conflicts |

**Trade-off to know:** Boot 4 is new, so many tutorials show Boot 3 code. If you hit a wall on a Boot 4 detail, check the official Boot 4.0 Migration Guide, which explains most old-to-new differences.

---

## 2. Prerequisites checklist

- [ ] JDK 21 (Eclipse Temurin), verify: `java -version`
- [ ] Maven is optional, because Initializr includes the wrapper (`./mvnw`)
- [ ] Docker Desktop (or Docker Engine), verify: `docker --version` and `docker compose version`
- [ ] Git, and your GitHub account (`github.com/VAISHNAV-1307`)
- [ ] IntelliJ IDEA Community (or VS Code with the Java extension pack)
- [ ] Telegram account on your phone
- [ ] LLM API key: Google AI Studio (Gemini) or Groq. Free-tier limits change often, so read the current limits before designing around them
- [ ] Gmail with 2-Step Verification, then create an App Password (only for the email backup channel)
- [ ] Oracle Cloud account (Phase 4). Signup needs identity/card verification, so confirm Always Free terms on Oracle's page
- [ ] Optional: healthchecks.io account (free dead-man's-switch ping)
- [ ] Your base resume content ready as plain text

---

## 3. PHASE 0: Project setup (2 to 3 days)

**0.1 Generate the project** at start.spring.io:

- Project: Maven | Language: Java | Spring Boot: latest stable 4.1.x | Packaging: Jar | Java: 21
- Group `io.github.vaishnav1307` | Artifact `jobpilot` | Name `JobPilot` | Package name `io.github.vaishnav1307.jobpilot`
- Dependencies: Spring Web, Spring Data JPA, PostgreSQL Driver, Flyway Migration, Validation, Spring Boot Actuator, Lombok, Testcontainers, Spring Boot DevTools
- Verify in the generated `pom.xml` that the web and Flyway starters have the Boot 4 names (`spring-boot-starter-webmvc`, `spring-boot-starter-flyway`). If Initializr didn't add `org.flywaydb:flyway-database-postgresql`, add it.

**0.2 Add jsoup** (`org.jsoup:jsoup`, latest) for cleaning HTML job descriptions.

**0.3 Local Postgres.** Create `docker-compose.yml` with a `postgres` service (current stable Postgres image), a named volume, `POSTGRES_DB/USER/PASSWORD` from a `.env` file, and port `5432` bound to `127.0.0.1` only. Run `docker compose up -d`.

**0.4 Configuration** in `application.yml`:

- `spring.datasource.*` from environment variables (`DB_URL`, `DB_USER`, `DB_PASS`)
- `spring.jpa.hibernate.ddl-auto: validate` (Flyway owns the schema), `spring.jpa.open-in-view: false`
- `spring.threads.virtual.enabled: true`
- `management.endpoints.web.exposure.include: health,info` and `management.server.address: 127.0.0.1` (Actuator is never public)
- Profiles: `dev` (verbose logging) and `prod`

**0.5 Git hygiene.** `git init`, then `.gitignore` containing `.env`, `data/`, `target/`, `*.pdf`, `.idea/`. **Never commit** your resume, phone number, API keys or tokens, even in a private repo, because history is permanent. Create the repo on GitHub, push, and add a README with the one-line purpose.

**0.6 Package skeleton** under `io.github.vaishnav1307.jobpilot`: `source`, `matching`, `tailoring`, `application`, `notification`, `scheduler`, `persistence`, `config`. Add `@EnableScheduling` and `@ConfigurationPropertiesScan` on a config class.

**0.7 Flyway baseline.** Create `src/main/resources/db/migration/V1__init.sql` with the schema in Appendix B.

**0.8 Smoke test.** App starts, `curl localhost:8080/actuator/health` shows `UP` (use the management port if you changed it), and the tables exist in Postgres.

**Done when:** a clean clone plus `docker compose up -d` plus `./mvnw spring-boot:run` starts without errors.

---

## 4. PHASE 1: Fetch, match, notify (1 to 1.5 weeks)

**1.1 Entities and repositories** (`persistence`): `Company`, `Job`, `Application` mapped to Appendix B. Statuses are an enum (Appendix C). Add `JobRepository.existsBySourceAndExternalId` and a query for jobs by status.

**1.2 Profile config** (`config`): a `@ConfigurationProperties` record `JobProfile` loaded from `profile.yml` (outside git, mounted at runtime) with:

- `skills`: list of `{name, aliases[], weight}`, e.g. Java, Spring Boot, Hibernate/JPA, REST, SQL, PostgreSQL, Docker, Kafka
- `titleInclude` and `titleExclude` keywords (e.g. include "java", "backend", "software engineer"; exclude whatever levels or roles you don't want)
- `preferredLocations` (cities, "India", "Remote") and `remoteOk`
- `maxRequiredYears` (your experience plus 1 is a sensible ceiling)
- `minScore` (start at 60 and tune later)

**1.3 Source abstraction** (`source`): `interface JobSource { String type(); List<NormalizedJob> fetch(Company c); }` and `record NormalizedJob(source, externalId, title, location, url, applyUrl, descriptionHtml, postedAt)`.

**1.4 GreenhouseSource**

- `GET https://boards-api.greenhouse.io/v1/boards/{token}/jobs?content=true` (public, no key)
- Fields: `id`, `title`, `location.name`, `absolute_url`, `updated_at`, `content`
- `content` arrives **HTML-entity-escaped**, so unescape it first, then pass it through jsoup to get plain text
- The board token is the slug in `boards.greenhouse.io/<token>` or `job-boards.greenhouse.io/<token>`
- Empty boards return `{"jobs":[]}` with HTTP 200
- Submitting an application through this API needs the *employer's* key, so you can't use it for applying (that's why Phase 3 uses a browser)

**1.5 LeverSource**

- `GET https://api.lever.co/v0/postings/{company}?mode=json` returns a **bare JSON array** (no wrapper object)
- EU-hosted boards live at `api.eu.lever.co`
- A missing company returns an error or an empty result, so handle both without crashing

**1.6 AshbySource**

- `GET https://api.ashbyhq.com/posting-api/job-board/{name}`
- **Skip postings with `isListed: false`** because the company hasn't published them

**1.7 Company list.** Put a seed file `companies.csv` (`name,source,token`) in your private data folder and import it on startup (upsert on `source+token`). To find tokens, open a company's careers page and look for `boards.greenhouse.io/<x>`, `jobs.lever.co/<x>` or `jobs.ashbyhq.com/<x>`. Start with 20 to 30 companies you'd actually want, and grow from there. Note that companies sometimes migrate ATS (e.g. Lever to Ashby).

**1.8 FetchService**

- For each active company, call the right `JobSource`, then upsert jobs.
- Dedupe on `(source, external_id)` and keep a `content_hash` to detect edits.
- **Baseline run (important):** on a company's first successful fetch, mark all its existing jobs `SEEDED` and notify nothing. Otherwise you get hundreds of alerts on day one. Set `company.baseline_done = true` afterward.
- Jobs that disappear from the feed get `CLOSED`.

**1.9 Scheduler**

- `@Scheduled(fixedDelayString = "PT15M")` (use `fixedDelay`, not `fixedRate`, so runs never overlap)
- Fetch companies in parallel on virtual threads, with a small token-bucket limiter you write yourself (about 4 requests/second total, 1 to 2 per host) and a descriptive `User-Agent`
- Per-company try/catch, so one broken board never kills the run
- `@Retryable` on the HTTP call (3 attempts, exponential backoff), and treat 429 as "back off for this host"
- Fetch once an hour at first, then tighten to 15 minutes once things are stable

**1.10 Matching** (`matching`)

1. **Hard filters:** title include/exclude, location (including remote policy), years-of-experience regex (take the *minimum* years stated, e.g. `(\d+)\s*(\+|-|to)?\s*(\d+)?\s*(years|yrs)`), reject if above `maxRequiredYears`.
2. **Normalize text:** lowercase, strip HTML, collapse whitespace.
3. **Skill extraction:** match each profile skill and its aliases as whole words (so "java" does not match "javascript").
4. **Score (0 to 100):** `100 × (0.60·skillCoverage + 0.25·niceToHave + 0.15·titleMatch)`, where `skillCoverage` is the weighted share of skills found in the JD that you have. Tune weights from real results.
5. Store the score, matched skills and missing skills on the job. Status becomes `MATCHED` if `score ≥ minScore`, else `LOW_SCORE`.

**1.11 Telegram setup**

- In Telegram, talk to **@BotFather**, run `/newbot`, and save the token in `.env` as `TELEGRAM_BOT_TOKEN`
- Send any message to your bot, call `https://api.telegram.org/bot<TOKEN>/getUpdates`, and note your numeric `chat.id` as `TELEGRAM_CHAT_ID`
- **Security rule:** the app must ignore every update whose chat id isn't yours

**1.12 TelegramNotifier** (`notification`): `sendMessage` through `RestClient`. Message format: title, company, location, score, matched and missing skills, apply link. Rules: keep messages under 4096 characters, send no more than about 1 message/second, and batch into a digest if more than 5 matches arrive at once. Mark the job `NOTIFIED` only after Telegram returns success.

**1.13 Observability.** Structured log per run: companies fetched, new jobs, matched, errors. Log request URL and status, never tokens.

**1.14 Tests**

- Save real sample responses from Greenhouse, Lever and Ashby into `src/test/resources/fixtures/` and test each parser against them
- Test scoring with table-driven cases ("Java" vs "JavaScript", "3-5 years", "5+ years", remote, hybrid)
- Repository tests with Testcontainers Postgres (`@ServiceConnection`)
- Fake the HTTP layer with `MockRestServiceServer`

**1.15 Burn-in.** Run it for 3 days and review every notification. Adjust skills, aliases, exclusions and `minScore` until about 80% of alerts feel relevant.

**Done when:** new matching jobs reach your phone within one polling interval, with zero duplicates and no first-run flood.

---

## 5. PHASE 2: Resume tailoring and approval flow (1 to 1.5 weeks)

**2.1 `resume.json`** (private data folder). Fields: `name`, `contact`, `summary`, `skills` (grouped), `experience[]` (company, title, dates, bullets), `projects[]`, `education[]`. This is the only source of facts.

**2.2 HTML template.** `resume.html` in Thymeleaf. Make it **ATS-safe**: single column, no tables for layout, no images or icons, real text, standard headings ("Experience", "Education", "Skills"), common fonts, one page if possible.

**2.3 PDF generation.** Use Playwright (Java): load the rendered HTML with `page.setContent(...)`, then `page.pdf(...)` with A4 size and print margins. Install browsers once with the Playwright CLI (`install --with-deps chromium`, run through `exec:java` on `com.microsoft.playwright.CLI`). Reuse a single `Browser` instance and create a new `Page` per job.

**2.4 LlmClient** (`tailoring`): `interface LlmClient { String complete(String system, String user); }` with `GeminiClient` and `GroqClient` (Groq uses an OpenAI-compatible chat-completions format). Config: provider, model, timeout, daily call cap. Wrap in `@Retryable` and handle 429 with backoff. **Fallback:** if the LLM fails, use the untailored base resume and still notify.

**2.5 Prompt design.** Inputs: `resume.json` plus the job description (truncate the JD to a fixed character limit). System rules:

- You may reorder, select and rephrase content that already exists in the resume.
- Do **not** add skills, tools, employers, dates, titles, metrics or degrees that are not in the resume.
- Return JSON only, in a given schema: `summary`, ordered `skills`, ordered `experienceBullets` per role, `projectsToInclude`.
- Temperature low (0 to 0.3).

**2.6 Hallucination guard (mandatory).** After parsing the LLM's JSON, validate in code:

- Every skill in the output exists in your profile skills (case-insensitive, alias-aware)
- Every employer, job title and date is unchanged
- Every number in a bullet appears in the original bullet
- Reject and retry once, else fall back to the base resume

**2.7 TailorService.** `MATCHED` → call LLM → validate → render → PDF to `data/out/<jobId>.pdf` → `TAILORED`. Cache by job id. Optionally generate a ≤120-word cover note grounded in the same facts.

**2.8 Telegram review message.** `sendDocument` with the tailored PDF and caption (title, company, score, key changes). Add an inline keyboard: **Apply** (`APPLY:<jobId>`), **Skip** (`SKIP:<jobId>`). Callback data must stay under 64 bytes.

**2.9 Callback handling.** A single long-poll loop using `getUpdates` (`timeout=30`, `offset=lastUpdateId+1`). Call `answerCallbackQuery` immediately. Handlers must be idempotent, so a double tap does nothing the second time. If you ever set a webhook, `getUpdates` returns 409, so call `deleteWebhook`.

**2.10 Email backup.** Add `spring-boot-starter-mail` and Gmail SMTP (`smtp.gmail.com`, port 587, STARTTLS, App Password). Use it only when Telegram fails.

**2.11 Tests.** Template renders for each skill combination, the guard rejects an invented skill, LLM failures fall back cleanly, callback parsing ignores a stranger's chat id.

**Done when:** a matched job produces a tailored PDF you'd honestly send, and tapping Apply or Skip updates its status.

---

## 6. PHASE 3: Semi-automatic applying (1 to 2 weeks)

**Reality check first:** forms differ per company, many have CAPTCHAs, custom questions or emailed verification codes, and layouts change. Treat auto-submit as best-effort with a manual fallback.

**3.1 Candidate data.** Private `candidate.yml` with name, email, phone, location, LinkedIn/GitHub URLs, notice period, work authorization, expected salary (optional). If a required question isn't answerable from this file, the adapter stops. It never guesses.

**3.2 Adapter interface** (`application`): `interface ApplyAdapter { boolean supports(String url); ApplyResult fill(Page page, Job job, Path resume); ApplyResult submit(Page page); }`. Implement `GreenhouseAdapter` first, then `AshbyAdapter` and `LeverAdapter`. Lever often shows hCaptcha, so expect frequent fallbacks.

**3.3 Playwright patterns.** Use label-based locators (`getByLabel`, `getByRole`) rather than brittle CSS ids. Upload with `setInputFiles`. Wait for visible states and avoid fixed sleeps. Use realistic delays and headless mode, and never try to bypass CAPTCHAs.

**3.4 Rules for questions**

- Contact fields: fill from `candidate.yml`
- Work-authorization and notice-period questions: fill from the file only if a value exists
- Demographic/EEO questions: choose "decline to answer" where offered
- Any other required free-text or unknown field: abort with `MANUAL_REQUIRED`

**3.5 Dry-run mode.** `applier.dry-run=true` by default. Fill the form, take a full-page screenshot, **do not submit**, send the screenshot to Telegram. Run in dry-run for a week across many companies before enabling submit.

**3.6 Approval gate.** Only the `APPROVED` status (from your Apply tap) allows a real submit.

**3.7 Submit and verify.** Click submit, wait for a confirmation page or text, take a screenshot, and set `APPLIED` only on positive confirmation. If unclear, mark `MANUAL_REQUIRED` and tell you.

**3.8 Failure handling.** On any exception: screenshot, HTML dump, `FAILED`, and a Telegram message with the link so you can apply manually in two minutes.

**3.9 Safety caps.** Max applications per day (start at 5 to 10), one application per company in a rolling window, a global kill-switch property, and a delay between submissions.

**3.10 Tests.** Run adapters against local static HTML copies of form structures, never against live company forms in automated tests.

**Done when:** in dry-run, at least 70% of Greenhouse and Ashby targets fill correctly with a correct screenshot, and every other case falls back cleanly.

---

## 7. PHASE 4: Deploy and operate (3 to 5 days)

**4.1 Dockerfile** (multi-stage): build on a Maven + JDK 21 image, then copy the jar into a runtime image that has the Playwright browsers. Option A: base the runtime on the official Playwright Java image whose tag matches your Playwright library version (confirm it ships a JDK 21+). Option B: use Temurin 21 and run the Playwright CLI install during the image build. Run as a non-root user. Ensure the image supports **arm64** if you use Oracle's ARM VM.

**4.2 Production compose.** Services: `app` and `postgres`. Both with `restart: unless-stopped`, secrets from a `.env` (chmod 600), volumes for Postgres data and `data/` (profile, resume, outputs), memory limits, and no published DB port.

**4.3 Oracle VM.** Create an Always Free Ampere A1 instance (check the current free limits on Oracle's page), Ubuntu LTS, SSH keys only. Open only port 22. The app needs **no inbound ports** because it polls Telegram outbound. Install Docker Engine, enable `ufw`, and enable unattended security upgrades. Capacity can be scarce in some regions, so retry or try another region. Oracle may reclaim Always Free instances that sit idle, so check their current policy and consider keeping the account in a state that avoids reclamation.

**4.4 CI/CD (GitHub Actions).** On push to `main`: `./mvnw verify`, build the image (multi-arch via buildx if needed), push to GitHub Container Registry, SSH to the VM, `docker compose pull && docker compose up -d`. Store the SSH key and tokens in GitHub Secrets.

**4.5 Monitoring.** (a) Telegram alert on repeated failures; (b) a daily heartbeat message ("fetched N, matched M, applied K"); (c) ping healthchecks.io after each successful run so you're alerted if the app silently dies; (d) log rotation.

**4.6 Backups.** Weekly `pg_dump` via cron into a compressed file, copied off the VM (private storage you control).

**4.7 Runbook.** Write down how to restart, read logs, roll back an image, rotate the Telegram token and LLM key, and pause applying (kill-switch).

**Done when:** the app survives a VM reboot, a Postgres restart, and a day of network errors without manual help.

---

## 8. PHASE 5: Optional extras

- **Kafka** (`spring-kafka`) between stages with topics like `job.new`, `job.matched`, `job.tailored`, `application.result`. It's overkill at this volume but good practice. Run it via compose and watch your VM's RAM.
- **Email alert ingestion:** read LinkedIn and Naukri alert emails over IMAP (via the mail starter) and extract links into the pipeline. This avoids scraping those sites.
- **More sources:** Adzuna API (check its India coverage and free limits), SmartRecruiters public postings.
- **Dashboard:** a small Thymeleaf page with application history (add authentication before exposing it anywhere).
- **Metrics:** Micrometer counters for fetched, matched, applied, failed.

---

## Appendix A: Configuration and secrets

| Name | Where | Notes |
| --- | --- | --- |
| `DB_URL`, `DB_USER`, `DB_PASS` | `.env` | Not committed |
| `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID` | `.env` | Ignore all other chat ids |
| `LLM_PROVIDER`, `LLM_API_KEY`, `LLM_MODEL` | `.env` | Per-day call cap in config |
| `MAIL_USER`, `MAIL_APP_PASSWORD` | `.env` | Gmail App Password |
| `applier.dry-run`, `applier.max-per-day`, `applier.enabled` | `application.yml` | Dry-run defaults to true |
| `profile.yml`, `candidate.yml`, `resume.json`, `companies.csv` | `data/` (mounted, git-ignored) | Contains personal info |

## Appendix B: Database schema (Flyway `V1__init.sql`)

```sql
create table company (
  id bigserial primary key,
  name text not null,
  source text not null,              -- GREENHOUSE | LEVER | ASHBY
  board_token text not null,
  active boolean not null default true,
  baseline_done boolean not null default false,
  unique (source, board_token)
);

create table job (
  id bigserial primary key,
  company_id bigint not null references company(id),
  source text not null,
  external_id text not null,
  title text not null,
  location text,
  url text not null,
  apply_url text,
  description_text text,
  content_hash text,
  posted_at timestamptz,
  first_seen_at timestamptz not null default now(),
  last_seen_at timestamptz not null default now(),
  match_score int,
  matched_skills text,
  missing_skills text,
  status text not null,
  unique (source, external_id)
);
create index idx_job_status on job(status);
create index idx_job_first_seen on job(first_seen_at);

create table application (
  id bigserial primary key,
  job_id bigint not null unique references job(id),
  resume_path text,
  cover_note text,
  status text not null,
  attempted_at timestamptz,
  applied_at timestamptz,
  error text,
  screenshot_path text
);
```

## Appendix C: Job status flow

`SEEDED` (baseline, ignored) | `NEW` → `LOW_SCORE` or `MATCHED` → `TAILORED` → `NOTIFIED` → `APPROVED` → `APPLYING` → `APPLIED`

Side exits: `SKIPPED` (you tapped Skip), `MANUAL_REQUIRED`, `FAILED`, `CLOSED` (job disappeared).

## Appendix D: Dependencies by phase

| Phase | Add |
| --- | --- |
| 0/1 | `spring-boot-starter-webmvc`, `spring-boot-starter-data-jpa`, `postgresql`, `spring-boot-starter-flyway` + `flyway-database-postgresql`, `spring-boot-starter-validation`, `spring-boot-starter-actuator`, `jsoup`, Lombok (optional), `spring-boot-starter-test`, Testcontainers (Postgres) via `spring-boot-testcontainers` |
| 2 | `spring-boot-starter-thymeleaf`, `spring-boot-starter-mail`, `com.microsoft.playwright:playwright` |
| 3 | Nothing new (Playwright already added) |
| 5 | `spring-kafka` (Boot 4 may offer a dedicated Kafka starter, so prefer what Initializr suggests) |

If `RestClient.Builder` isn't auto-configured in Boot 4, add `spring-boot-starter-restclient`.

## Appendix E: Common problems

- **App fails at startup with a DataSource error:** Postgres isn't running or `DB_URL` is wrong.
- **Flyway "no database found / unsupported":** add `flyway-database-postgresql`.
- **Jackson import errors (`com.fasterxml.jackson.databind` not found):** use `tools.jackson.databind` in Boot 4.
- **Telegram `getUpdates` returns 409:** a webhook is set, so call `deleteWebhook`.
- **First run sends hundreds of alerts:** the baseline step (1.8) is missing.
- **Playwright can't find a browser:** run the CLI browser install, and check the Docker image tag matches the library version.
- **LLM returns 429:** back off, lower the call cap, and rely on the base-resume fallback.
- **Duplicate notifications:** check the `(source, external_id)` unique key and that `NOTIFIED` is set after a successful send.

## Appendix F: Rules to keep this safe and useful

1. Public job-board APIs only. Don't scrape LinkedIn or Naukri.
2. Never invent resume content. The guard in 2.6 enforces it.
3. Never bypass CAPTCHAs or verification steps.
4. Every real submission needs your explicit Apply tap.
5. Keep daily caps. Ten thoughtful applications beat a hundred automated ones.
6. Keep personal data and secrets out of git.

## Appendix G: Where this project teaches your learning goals

- **Multithreading/concurrency:** parallel fetch on virtual threads, your own token-bucket limiter, idempotent callback handling
- **Design patterns:** Strategy (`JobSource`, `ApplyAdapter`, `LlmClient`), Factory, Template Method (apply flow), Observer (notifications)
- **Kafka:** Phase 5
- **SQL/JPA:** schema, indexes, upserts, status queries
- **Testing:** fixtures, Testcontainers, table-driven tests
- **DevOps:** Docker, GitHub Actions, Linux VM hardening