import path from 'path';
import { expect, test } from '../../fixtures/auth';

// Run after the real emulator scenarios, using the pinned backend's normal auth fixture.
for (const [source, kind, symbol] of [
	['crash', 'Crash', 'Checkout.pay'],
	['anr', 'ANR', 'MainActivity'],
] as const) {
	test(`TC-${source === 'crash' ? '02' : '03'} Android ${source} is retraced in Errors`, async ({ authedPage: page }) => {
		const id = process.env.FULL_APPLICATION_ID;
		expect(id).toBeTruthy();
		await page.goto(`/frontend/applications/${id}/errors?relativeTime=1h`);
		const row = page.getByTestId('rum-error-group').filter({
			has: page.getByTestId('rum-error-group-kind').filter({ hasText: new RegExp(`^${kind}$`) }),
		});
		await expect(row).toHaveCount(1);
		await row.getByTestId('rum-error-group-open').click();
		await expect(page.getByTestId('rum-error-kind')).toHaveText(kind);
		await expect(page.getByTestId('rum-error-mode-original')).toBeEnabled();
		await expect(page.getByTestId('rum-error-stack')).toContainText(symbol);
		await expect(page.getByTestId('rum-error-missing-map')).toHaveCount(0);
		await page.screenshot({
			path: path.join(process.env.NATIVE_ACCEPTANCE_DIR!, 'evidence', `errors-${source}.png`),
			fullPage: true,
		});
	});
}


test('TC-04 Android app start is visible in the session timeline', async ({ authedPage: page }) => {
	test.skip(process.env.REQUIRE_APP_START_TIMELINE !== 'true', 'Requires a backend containing #312');
	const id = process.env.FULL_APPLICATION_ID;
	const session = process.env.FULL_SESSION_ID;
	expect(id).toBeTruthy();
	expect(session).toBeTruthy();
	await page.goto(`/frontend/applications/${id}/sessions/${session}?relativeTime=1h`);
	await expect(page.getByTestId('rum-session-id')).toContainText(session!);
	await page.getByTestId('rum-session-kind-vitals').click();
	const appStart = page.locator('[data-testid="rum-session-event"][data-kind="vital"]').filter({ hasText: /App start [\d.]+ (ms|s)/ });
	await expect(appStart.first()).toBeVisible();
	await expect(appStart.first()).toContainText(/Good|Needs improvement|Poor/);
	await page.screenshot({ path: path.join(process.env.NATIVE_ACCEPTANCE_DIR!, 'evidence', 'session-app-start.png'), fullPage: true });
});
