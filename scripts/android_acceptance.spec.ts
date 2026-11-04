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
