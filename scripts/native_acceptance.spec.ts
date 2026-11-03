import { expect, test } from '../../fixtures/auth';

// Copied into the pinned backend's tests/e2e/tests/rum by the acceptance workflow.
test('TC-01 native SDK release appears grouped with frames in Errors', async ({
	authedPage: page,
}) => {
	const id = process.env.NATIVE_APPLICATION_ID;
	expect(id).toBeTruthy();
	await page.goto(
		`/frontend/applications/${id}/errors?relativeTime=1h&source=native`,
	);
	await expect(page.getByTestId('rum-error-group')).toHaveCount(1);
	await expect(page.getByTestId('rum-error-group-kind')).toHaveText(
		'Native crash',
	);
	await page.getByTestId('rum-error-group-open').click();
	await expect(page.getByTestId('rum-error-type')).toHaveText('SIGABRT');
	await expect(page.getByTestId('rum-error-kind')).toHaveText('Native crash');
	await expect(page.getByTestId('rum-error-stack')).toContainText(
		/pc 0x[0-9a-f]+/,
	);
	await expect(page.getByTestId('rum-error-missing-map')).toHaveCount(0);
	await expect(page.getByTestId('rum-error-mode-minified')).toHaveCount(0);
	await page.screenshot({ path: process.env.NATIVE_SCREENSHOT, fullPage: true });
});
