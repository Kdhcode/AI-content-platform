const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');

async function main() {
  const visible = process.env.SHOW_PUBLIC_NEWS === '1';
  const browser = await chromium.launch({ channel: 'chrome', headless: !visible });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
    const errors = [];
    let credentialSent = false;
    page.on('pageerror', error => errors.push(error.message));
    page.on('request', request => {
      if (request.url().includes('/api/public/') && request.headers().authorization) credentialSent = true;
    });
    const base = 'http://localhost:3000';
    const output = path.resolve(__dirname, '../../.local/screenshots');
    fs.mkdirSync(output, { recursive: true });
    await page.goto(base + '/news');
    await page.locator('.news-card').first().waitFor();
    assert.equal(await page.locator('.news-card').count(), 12);
    await page.screenshot({ path: path.join(output, 'public-news-desktop.png'), fullPage: true });
    const firstTitle = await page.locator('.news-card h2').first().textContent();
    const firstHref = await page.locator('.news-card').first().getAttribute('href');
    await page.getByRole('button', { name: '다음 →', exact: true }).click();
    await page.locator('.news-pagination').getByText(/^2 \/ /).waitFor();
    await page.getByRole('button', { name: '이전', exact: false }).click();
    await page.locator('.news-pagination').getByText(/^1 \/ /).waitFor();
    await page.getByRole('textbox', { name: '이슈 검색', exact: true }).fill('no-results-' + Date.now());
    await page.getByRole('button', { name: '검색 ↗', exact: true }).click();
    await page.getByRole('heading', { name: '검색 결과가 없습니다.' }).waitFor();
    await page.getByRole('button', { name: '전체 이슈 보기' }).click();
    await page.locator('.news-card').first().waitFor();
    await page.getByRole('textbox', { name: '이슈 검색', exact: true }).fill(firstTitle);
    await page.getByRole('button', { name: '검색 ↗', exact: true }).click();
    await page.locator('.news-card h2').getByText(firstTitle, { exact: true }).first().waitFor();
    await page.goto(base + firstHref);
    await page.locator('.news-detail-heading h1').waitFor();
    assert.equal(await page.locator('.news-detail-heading h1').textContent(), firstTitle);
    await page.locator('.news-source-article').first().waitFor();
    const link = page.locator('.news-source-article a').first();
    assert.match(await link.getAttribute('href'), /^https?:\/\//);
    assert.equal(await link.getAttribute('target'), '_blank');
    await page.screenshot({ path: path.join(output, 'public-news-detail.png'), fullPage: true });
    await page.goto(base + '/news/999999999');
    await page.getByRole('heading', { name: '공개된 이슈를 찾을 수 없습니다.' }).waitFor();
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(base + '/news');
    await page.locator('.news-card').first().waitFor();
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
    await page.screenshot({ path: path.join(output, 'public-news-mobile.png'), fullPage: true });
    await page.goto(base + firstHref);
    await page.locator('.news-detail-heading h1').waitFor();
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
    assert.equal(credentialSent, false);
    assert.deepEqual(errors, []);
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.goto(base + '/news');
    await page.locator('.news-card').first().waitFor();
    console.log('PASS: public list, pagination, search, empty results, details, source links, 404, mobile, no auth headers');
    if (visible) await new Promise(resolve => browser.on('disconnected', resolve));
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });
