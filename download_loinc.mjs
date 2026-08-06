import fs from 'fs';
import path from 'path';
import puppeteer from 'puppeteer-extra';
import StealthPlugin from 'puppeteer-extra-plugin-stealth';

puppeteer.use(StealthPlugin());

const downloadPath = path.resolve('.'); // Set download directory

if (!fs.existsSync(downloadPath)) {
    fs.mkdirSync(downloadPath, { recursive: true });
}

async function setupBrowser() {
    const browser = await puppeteer.launch({ headless: true, args:['--no-sandbox'] });
    const page = await browser.newPage();

    // Set a realistic User-Agent to avoid being blocked
    await page.setUserAgent('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36');

    const client = await page.createCDPSession();
    await client.send('Page.setDownloadBehavior', {
        behavior: 'allow',
        downloadPath: downloadPath
    });

    return { browser, page };
}

async function login(page) {
    console.log('Navigating to LOINC login page...');
    await page.goto('https://loinc.org/login', { waitUntil: 'networkidle0' });

    console.log('Filling in credentials...');
    await page.type('#user_login', process.env.LOINC_USERNAME);
    await page.type('#user_pass', process.env.LOINC_PASSWORD);

    console.log('Submitting login form...');
    await Promise.all([
        page.click('#wp-submit'),
        page.waitForNavigation({ waitUntil: 'networkidle2' }),
    ]);

    console.log('Login successful!');
}

async function downloadLatestReleaseFile(page) {
    console.log('Starting download of the latest release...');

    console.log("Navigating to downloads...");
    await page.goto('https://loinc.org/downloads', { waitUntil: 'networkidle0', timeout: 60000 });

    const downloadSelector = '#download-link-1321';
    console.log("Clicking download link...");
    await page.click(downloadSelector);

    console.log('Waiting for download to complete...');
    const fileDownloaded = await waitForDownload(downloadPath, 120000);

    if (fileDownloaded) {
        console.log('LOINC download completed!');
    } else {
        console.log('LOINC Download timeout reached.');
    }
}

async function downloadSpecificReleaseFile(page, version) {
    console.log('Navigating to LOINC archive page...');
    await page.goto('https://loinc.org/downloads/archive/', { waitUntil: 'networkidle2' });

    console.log('Searching for version ' + version + '...');

    const downloadSelector = `a[href*='loinc-${version.replace(/\./g, "-")}']`;

    await page.waitForSelector(downloadSelector);

    console.log('Clicking download link...');
    await page.click(downloadSelector);

    console.log('Waiting for download to complete...');
    const fileDownloaded = await waitForDownload(downloadPath, 120000);

    if (fileDownloaded) {
        console.log(`LOINC version ${version} downloaded successfully!`);
    } else {
        console.log(`Timeout reached. LOINC version ${version} download failed.`);
    }
}


async function waitForDownload(dir, timeout = 120000) {
    const start = Date.now();

    while (Date.now() - start < timeout) {
        const files = await fs.promises.readdir(dir);
        if (files.some(file => file.startsWith('Loinc') && file.endsWith('.zip'))) {
            return true; // Download complete
        }
        await new Promise(resolve => setTimeout(resolve, 1000)); // Wait 1 sec before checking again
    }

    return false; // Timeout reached
}

async function main() {
    try {
        const version = process.argv[2];
        const { browser, page } = await setupBrowser();
        await login(page);
        if(version) {
            await downloadSpecificReleaseFile(page, version);
        } else {
            await downloadLatestReleaseFile(page);
        }
        await browser.close();
    } catch (error) {
        console.error('An error occurred:', error);
    }
}

await main();
