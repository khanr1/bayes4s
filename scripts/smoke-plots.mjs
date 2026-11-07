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

const target = process.argv[2]
if (!target) {
  console.error('usage: node scripts/smoke-plots.mjs <page.html>')
  process.exit(2)
}

const browser = await chromium.launch()
const page = await browser.newPage()
const complaints = []
page.on('console', message => {
  if (message.type() === 'error') complaints.push(`console: ${message.text()}`)
})
page.on('pageerror', error => complaints.push(`thrown: ${error}`))
page.on('requestfailed', request => complaints.push(`not loaded: ${request.url()}`))

const url = pathToFileURL(resolve(target)).href
await page.goto(url, { waitUntil: 'load' })

const figures = await page.locator('.figure').count()
if (figures === 0) {
  console.error(`${target} has no figures at all`)
  await browser.close()
  process.exit(1)
}

let drawn = 0
try {
  await page.waitForFunction(
    expected => document.querySelectorAll('.figure canvas, .figure svg').length >= expected,
    figures,
    { timeout: 120000 }
  )
  drawn = figures
} catch {
  drawn = await page.locator('.figure canvas, .figure svg').count()
}

const reported = (await page.locator('#errors').textContent()).trim()
if (reported) complaints.push(`in the page: ${reported}`)
if (drawn < figures) complaints.push(`${drawn} of ${figures} figures were drawn`)

await browser.close()

if (complaints.length > 0) {
  console.error(`${target}: ${complaints.length} problem(s)`)
  for (const complaint of complaints) console.error(`  ${complaint}`)
  process.exit(1)
}
console.log(`${target}: ${figures} figures drawn, no Vega error`)
