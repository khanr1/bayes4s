// A browser's opinion of a page of figures.
//
// Everything else about a plot is tested as a value: the statistics against their references, the
// specification against Vega-Lite's own schema, the page against its own renderer. None of that
// can say whether Vega *runs* the specification — an encoding that validates and still throws, a
// field a transform cannot find, a version pinned to something that no longer loads. So this
// opens the page in Chromium, waits for every figure to draw, and fails on anything written to
// the console, thrown, or reported into the page's own error box.
//
// Never compares images (CLAUDE.md): what it asserts is that each figure produced a drawing and
// that nothing complained.
import { chromium } from 'playwright'
import { resolve } from 'node:path'
import { pathToFileURL } from 'node:url'

const targets = process.argv.slice(2)
if (targets.length === 0) {
  console.error('usage: node scripts/smoke-plots.mjs <page.html> [more.html ...]')
  process.exit(2)
}

// The demonstration page marks its containers `.figure`; a manual page built by `Html.embed`
// marks them `.bayes4s-figure`. Both are figures and both have to draw.
const SELECTOR = '.figure, .bayes4s-figure'

const browser = await chromium.launch()
let failed = false

for (const target of targets) {
  const page = await browser.newPage()
  const complaints = []
  page.on('console', message => {
    if (message.type() === 'error') complaints.push(`console: ${message.text()}`)
  })
  page.on('pageerror', error => complaints.push(`thrown: ${error}`))
  page.on('requestfailed', request => complaints.push(`not loaded: ${request.url()}`))

  await page.goto(pathToFileURL(resolve(target)).href, { waitUntil: 'load' })

  const figures = await page.locator(SELECTOR).count()
  if (figures === 0) {
    // Not a failure: most manual pages have nothing to draw. Which pages *must* carry a figure is
    // a question about the manual, and scripts/check-manual.py is where that is asserted.
    console.log(`${target}: no figures`)
    await page.close()
    continue
  }

  const drawnSelector = '.figure canvas, .figure svg, .bayes4s-figure canvas, .bayes4s-figure svg'
  let drawn = 0
  try {
    await page.waitForFunction(
      ([selector, expected]) => document.querySelectorAll(selector).length >= expected,
      [drawnSelector, figures],
      { timeout: 120000 }
    )
    drawn = figures
  } catch {
    drawn = await page.locator(drawnSelector).count()
  }

  // Both kinds of page write their failures where a reader can see them: the demonstration page
  // into `#errors`, a manual page into the figure's own container.
  const box = page.locator('#errors')
  const reported = (await box.count()) > 0 ? (await box.textContent()).trim() : ''
  if (reported) complaints.push(`in the page: ${reported}`)
  const broken = await page.locator('text=figure failed:').count()
  if (broken > 0) complaints.push(`${broken} figure(s) reported a failure in the page`)
  if (drawn < figures) complaints.push(`${drawn} of ${figures} figures were drawn`)

  await page.close()

  if (complaints.length > 0) {
    console.error(`${target}: ${complaints.length} problem(s)`)
    for (const complaint of complaints) console.error(`  ${complaint}`)
    failed = true
  } else {
    console.log(`${target}: ${figures} figures drawn, no Vega error`)
  }
}

await browser.close()
if (failed) process.exit(1)
