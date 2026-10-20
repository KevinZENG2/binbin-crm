// Real browser acceptance uses only the disposable CI API and synthetic accounts.
import {createRequire} from 'node:module';import assert from 'node:assert/strict';import {mkdir,writeFile} from 'node:fs/promises';
assert.equal(process.env.CRM_ISOLATED_ACCEPTANCE,'true');
const base=process.env.CRM_ACCEPTANCE_URL||'http://127.0.0.1:8080';assert.ok(['localhost','127.0.0.1'].includes(new URL(base).hostname));
const {chromium}=createRequire(import.meta.url)(process.env.CRM_PLAYWRIGHT_MODULE||'playwright');
const browser=await chromium.launch({headless:true});const page=await browser.newPage({viewport:{width:1440,height:1000}});const errors=[];page.on('pageerror',e=>errors.push(e.message));
const out=process.env.CRM_UI_RESULT_DIR||'p7a-ui';await mkdir(out,{recursive:true});const checks=[];
try{
 await page.goto(base+'/?account=owner');await page.getByLabel('账号',{exact:true}).fill('owner');await page.getByLabel('密码',{exact:true}).fill(process.env.CRM_TEST_PASSWORD);await page.getByRole('button',{name:'登录工作区',exact:true}).click();
 await page.getByLabel('当前企业',{exact:true}).selectOption('00000000-0000-4000-8000-000000000001');
 await page.getByRole('navigation',{name:'工作区导航'}).getByRole('button',{name:'经营指挥台',exact:true}).click();await page.locator('.analytics-kpis').waitFor();
 await page.getByRole('button',{name:'本季度',exact:true}).click();await page.locator('.analytics-kpis').waitFor();checks.push('Login and left navigation open real analytics with a quarterly filter');
 await page.screenshot({path:out+'/desktop.png',fullPage:true});
 await page.locator('.analytics-kpis button').filter({hasText:'确认订单'}).click();await page.locator('[role=dialog] tbody tr').filter({hasText:'P6销售订单'}).waitFor();
 let rect=await page.getByRole('dialog').boundingBox();assert.ok(rect&&rect.x>=0&&rect.y>=0&&Math.abs(rect.x+rect.width/2-720)<5&&rect.y+rect.height<=1000);checks.push('Order drilldown contains real fixture and stays centered within desktop viewport');
 await page.screenshot({path:out+'/order-drilldown.png',fullPage:true});await page.keyboard.press('Escape');await page.getByRole('dialog').waitFor({state:'hidden'});
 await page.getByRole('button',{name:'销售与产品',exact:true}).click();await page.locator('.analytics-panel').filter({has:page.getByRole('heading',{name:'产品销售',exact:true})}).getByRole('button').filter({hasText:'P6验收服务'}).click();
 await page.locator('[role=dialog] tbody tr').filter({hasText:'P6验收服务'}).waitFor();checks.push('Product chart supports keyboard-accessible drilldown');await page.keyboard.press('Escape');await page.getByRole('dialog').waitFor({state:'hidden'});
 await page.setViewportSize({width:390,height:844});await page.getByRole('button',{name:'客户经营',exact:true}).click();await page.screenshot({path:out+'/mobile.png',fullPage:true});
 assert.ok(await page.locator('.analytics-workspace').evaluate(el=>el.getBoundingClientRect().right<=window.innerWidth+2));
 await page.locator('.analytics-panel').first().getByRole('button',{name:'查看明细'}).click();await page.getByRole('dialog').waitFor();await page.locator('[role=dialog]').getByText('正在加载明细…').waitFor({state:'hidden'});
 rect=await page.getByRole('dialog').boundingBox();assert.ok(rect&&rect.x>=0&&rect.y>=0&&rect.x+rect.width<=391&&rect.y+rect.height<=845);await page.screenshot({path:out+'/mobile-drilldown.png',fullPage:true});checks.push('Mobile analytics and drilldown fit the viewport with internal table scrolling');
 assert.deepEqual(errors,[]);checks.push('No browser runtime exceptions');await writeFile(out+'/result.json',JSON.stringify({stage:'P7-A UI',passed:checks.length,checks},null,2)+'\n');console.log(checks.join('\n'));
}catch(error){await page.screenshot({path:out+'/failure.png',fullPage:true});await writeFile(out+'/failure.html',await page.content());throw error;}finally{await browser.close();}
