#!/bin/sh
set -eu

base="${CRM_API_URL:-http://127.0.0.1:8080/api/v1}"
work="$(mktemp -d /tmp/crm-p4-api.XXXXXX)"
trap 'rm -rf "$work"' EXIT

json() { jq -e "$2" "$1" >/dev/null; }
post() { curl -fsS -b "$work/internal.cookies" -H 'Content-Type: application/json' -H "X-CSRF-Token: $csrf" -H "X-Workspace-ID: $tenant" "$@"; }

curl -fsS "$base/health" | jq -e '.status=="UP" and .version=="0.13.0"' >/dev/null
login="$(curl -fsS -c "$work/internal.cookies" -H 'Content-Type: application/json' --data "$(jq -nc --arg password "$CRM_TEST_PASSWORD" '{username:"owner",password:$password}')" "$base/auth/login")"
printf '%s' "$login" > "$work/login.json"
csrf="$(jq -er '.csrfToken' "$work/login.json")"
tenant="$(jq -er '.tenants[]|select(.code=="test-1")|.id' "$work/login.json")"
workspace="$(post --data "$(jq -nc --arg tenant "$tenant" '{tenantId:$tenant}')" "$base/auth/active-tenant")"
printf '%s' "$workspace" > "$work/workspace.json"
csrf="$(jq -er '.csrfToken' "$work/workspace.json")"
json "$work/workspace.json" '.p4Access.configurationRead and .p4Access.configurationManage and .p4Access.portalAccountManage and .p4Access.aiUse and .p4Access.aiReview'

curl -fsS -b "$work/internal.cookies" -H "X-Workspace-ID: $tenant" "$base/configuration" > "$work/configuration.json"
json "$work/configuration.json" '.profile.portalEnabled and (.templates|length)>=4 and (.numberRules|length)==5 and (.approvalRule.processType=="QUOTE")'
post -X POST "$base/configuration/templates/MANUFACTURING/apply" > "$work/template.json"
json "$work/template.json" '.code=="MANUFACTURING" and .fieldsCreated>=1 and .ticketTypesApplied>=1'

tag_body='{"name":"P4隔离验收","color":"#2E7D5B","status":"ACTIVE","expectedVersion":0}'
post -X POST -H 'Idempotency-Key: p4-acceptance-tag' --data "$tag_body" "$base/configuration/tags" > "$work/tag-1.json"
post -X POST -H 'Idempotency-Key: p4-acceptance-tag' --data "$tag_body" "$base/configuration/tags" > "$work/tag-2.json"
test "$(jq -er .id "$work/tag-1.json")" = "$(jq -er .id "$work/tag-2.json")"
curl -fsS -b "$work/internal.cookies" -H "X-Workspace-ID: $tenant" "$base/portal-accounts" | jq -e 'length>=1 and any(.[];.email=="contact@huaxi.example.test")' >/dev/null

code="$(curl -sS -o "$work/separation.json" -w '%{http_code}' -b "$work/internal.cookies" "$base/portal/auth/session")"
test "$code" = 401
portal_login="$(curl -fsS -c "$work/portal.cookies" -H 'Content-Type: application/json' --data "$(jq -nc --arg password "$CRM_TEST_PASSWORD" '{tenantCode:"test-1",email:"contact@huaxi.example.test",password:$password}')" "$base/portal/auth/login")"
printf '%s' "$portal_login" > "$work/portal-login.json"
portal_csrf="$(jq -er '.csrfToken' "$work/portal-login.json")"
json "$work/portal-login.json" '.tenant.code=="test-1" and .account.email=="contact@huaxi.example.test" and (.account.mustChangePassword|not)'
curl -fsS -b "$work/portal.cookies" "$base/portal/summary" > "$work/summary.json"
json "$work/summary.json" '.customer.name=="华熙装备（虚构测试客户）" and (.counts.tickets|type)=="number"'

ticket_body='{"title":"P4客户门户隔离验收","description":"仅写入一次的虚构验收工单","priority":"NORMAL","typeCode":"GENERAL"}'
curl -fsS -b "$work/portal.cookies" -H 'Content-Type: application/json' -H "X-CSRF-Token: $portal_csrf" -H 'Idempotency-Key: p4-acceptance-ticket' --data "$ticket_body" "$base/portal/tickets" > "$work/ticket-1.json"
curl -fsS -b "$work/portal.cookies" -H 'Content-Type: application/json' -H "X-CSRF-Token: $portal_csrf" -H 'Idempotency-Key: p4-acceptance-ticket' --data "$ticket_body" "$base/portal/tickets" > "$work/ticket-2.json"
test "$(jq -er .id "$work/ticket-1.json")" = "$(jq -er .id "$work/ticket-2.json")"
json "$work/ticket-1.json" '.typeCode=="GENERAL" and .status=="NEW" and (.sla.state=="ACTIVE")'
curl -fsS -b "$work/portal.cookies" "$base/portal/tickets?pageSize=100" | jq -e --arg id "$(jq -er .id "$work/ticket-1.json")" 'any(.items[];.id==$id)' >/dev/null
curl -fsS -b "$work/portal.cookies" "$base/portal/knowledge?pageSize=100" | jq -e '.items|type=="array"' >/dev/null

curl -fsS -b "$work/internal.cookies" -H "X-Workspace-ID: $tenant" "$base/ai/status" > "$work/ai-status.json"
json "$work/ai-status.json" '.planEnabled and (.monthlyLimit|type)=="number" and (.monthlyUsed|type)=="number" and (.configured|not)'
customer_id="$(curl -fsS -b "$work/internal.cookies" -H "X-Workspace-ID: $tenant" "$base/customers/options?pageSize=1&writable=false" | jq -er '.items[0].id')"
ai_body="$(jq -nc --arg id "$customer_id" '{taskType:"CUSTOMER_SUMMARY",recordId:$id,instructions:"仅用于隔离验收",dataApproved:true}')"
post -X POST -H 'Idempotency-Key: p4-ai-disabled-acceptance' --data "$ai_body" "$base/ai/runs" > "$work/ai-1.json"
post -X POST -H 'Idempotency-Key: p4-ai-disabled-acceptance' --data "$ai_body" "$base/ai/runs" > "$work/ai-2.json"
test "$(jq -er .id "$work/ai-1.json")" = "$(jq -er .id "$work/ai-2.json")"
json "$work/ai-1.json" '.status=="BLOCKED" and .errorCode=="AI_PROVIDER_NOT_CONFIGURED" and (.resultText==null)'

platform_login="$(curl -fsS -c "$work/platform.cookies" -H 'Content-Type: application/json' --data "$(jq -nc --arg password "$CRM_TEST_PASSWORD" '{username:"platform-operator",password:$password}')" "$base/auth/login")"
printf '%s' "$platform_login" > "$work/platform-login.json"
platform_csrf="$(jq -er '.csrfToken' "$work/platform-login.json")"
curl -fsS -b "$work/platform.cookies" "$base/platform/commercial/overview" > "$work/commercial.json"
json "$work/commercial.json" '.paymentMode=="MANUAL_ONLY" and (.plans|length)>=1 and (.metrics.activeTenants|type)=="number"'
plan_id="$(jq -er '.plans[0].id' "$work/commercial.json")"
due="$(date -u -d '+14 days' +%F)"
order_body="$(jq -nc --arg tenant "$tenant" --arg plan "$plan_id" --arg due "$due" '{tenantId:$tenant,planId:$plan,periodMonths:1,amountCents:null,dueDate:$due,notes:"P4 隔离验收后取消"}')"
curl -fsS -b "$work/platform.cookies" -H 'Content-Type: application/json' -H "X-CSRF-Token: $platform_csrf" -H 'Idempotency-Key: p4-commercial-order-acceptance' --data "$order_body" "$base/platform/commercial/orders" > "$work/order-1.json"
curl -fsS -b "$work/platform.cookies" -H 'Content-Type: application/json' -H "X-CSRF-Token: $platform_csrf" -H 'Idempotency-Key: p4-commercial-order-acceptance' --data "$order_body" "$base/platform/commercial/orders" > "$work/order-2.json"
test "$(jq -er .id "$work/order-1.json")" = "$(jq -er .id "$work/order-2.json")"
order_id="$(jq -er .id "$work/order-1.json")";order_version="$(jq -er .version "$work/order-1.json")"
curl -fsS -b "$work/platform.cookies" -H 'Content-Type: application/json' -H "X-CSRF-Token: $platform_csrf" --data "$(jq -nc --argjson version "$order_version" '{reason:"隔离验收清理",expectedVersion:$version}')" "$base/platform/commercial/orders/$order_id/cancel" | jq -e '.status=="CANCELLED"' >/dev/null

printf '%s\n' 'CRM_P4_API_ACCEPTANCE {"health":"0.13.0","configuration":true,"template":true,"idempotency":true,"portalIdentitySeparated":true,"portalCustomerScope":true,"portalTicket":true,"knowledgePublishedOnly":true,"aiAudit":true,"commercialOperations":true}'
