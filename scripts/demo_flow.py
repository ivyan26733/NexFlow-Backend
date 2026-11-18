"""Generates SQL that creates the NexFlow demo flows (every node type) for one user.

Creates "Demo · Customer Health Report" (slug demo-customer-report) and its child flow
"Demo · Score Customers". Re-running replaces both. The AI step uses the user's GROQ provider.

    python3 scripts/demo_flow.py <user-uuid> > demo.sql
    psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f demo.sql
    curl -X POST <backend>/api/pulse/demo-customer-report -H 'Content-Type: application/json' -d '{}'
"""
import json
import sys
import uuid

USER_ID = sys.argv[1]
MAIN_ID = str(uuid.uuid4())
CHILD_ID = str(uuid.uuid4())

nodes, edges = [], []


def node(flow, ntype, label, x, y, config=None):
    nid = str(uuid.uuid4())
    nodes.append((nid, flow, ntype, label, x, y, config or {}))
    return nid


def edge(flow, src, dst, cond="DEFAULT", handle=None):
    edges.append((str(uuid.uuid4()), flow, src, dst, cond, handle))


# ── Child flow: Demo · Score Customers (called by the SUB_FLOW step) ────────────
c_start = node(CHILD_ID, "START", "Start", 0, 0)
c_tier = node(CHILD_ID, "SCRIPT", "Assign Tiers", 0, 160, {
    "language": "javascript",
    "code": """// Receives {customers, minRevenue} from the parent flow
const min = Number(nex.minRevenue ?? 1000);
const customers = (nex.customers ?? []).map(c => ({
  ...c,
  tier: c.revenue >= min * 1.3 ? 'VIP' : c.revenue >= min ? 'Gold' : 'Standard',
}));
customers.sort((a, b) => b.revenue - a.revenue);
return {
  customers,
  vipCount: customers.filter(c => c.tier === 'VIP').length,
  goldCount: customers.filter(c => c.tier === 'Gold').length,
  topCustomer: customers[0]?.name ?? 'n/a',
};""",
})
c_done = node(CHILD_ID, "SUCCESS", "Scored", 0, 320, {
    "response": {"vipCount": "{{nex.assignTiers.result.vipCount}}", "topCustomer": "{{nex.assignTiers.result.topCustomer}}"},
})
c_fail = node(CHILD_ID, "FAILURE", "Scoring Failed", 300, 320, {
    "response": {"error": "{{nex.assignTiers.error}}"},
})
edge(CHILD_ID, c_start, c_tier)
edge(CHILD_ID, c_tier, c_done, "SUCCESS", "success")
edge(CHILD_ID, c_tier, c_fail, "FAILURE", "failure")

# ── Main flow: Demo · Customer Health Report ────────────────────────────────────
M = MAIN_ID
start = node(M, "START", "Start", 0, 0)
settings = node(M, "VARIABLE", "Report Settings", 0, 150, {"variables": {
    "reportTitle": "Customer Health Report",
    "minRevenue": 1000,
    "forecastWeeks": 3,
    "growthRate": 1.08,
}})
fetch = node(M, "NEXUS", "Fetch Customers", 0, 300, {
    "url": "https://jsonplaceholder.typicode.com/users",
    "method": "GET",
    "headers": {"Accept": "application/json"},
    "body": {},
})
api_down = node(M, "FAILURE", "Customer API Down", 380, 450, {"response": {
    "message": "Could not reach the customer API",
    "error": "{{nex.fetchCustomers.error}}",
}})
clean = node(M, "SCRIPT", "Clean Customers", 0, 450, {
    "language": "javascript",
    "code": """// Turn the raw API users into tidy customer records with order stats
const users = nex.fetchCustomers?.body ?? [];
const customers = users.map(u => {
  const orders = ((u.id * 7) % 23) + 3;
  const avgOrder = 40 + ((u.id * 13) % 60);
  return {
    id: u.id,
    name: u.name,
    email: u.email.toLowerCase(),
    city: u.address?.city,
    company: u.company?.name,
    orders,
    revenue: orders * avgOrder,
  };
});
const totalRevenue = customers.reduce((sum, c) => sum + c.revenue, 0);
return {
  customers,
  count: customers.length,
  totalRevenue,
  avgRevenue: Math.round(totalRevenue / Math.max(customers.length, 1)),
};""",
})
clean_failed = node(M, "FAILURE", "Processing Failed", 380, 600, {"response": {
    "message": "Cleaning the customer data failed",
    "error": "{{nex.cleanCustomers.error}}",
}})
any_customers = node(M, "DECISION", "Any Customers", 0, 600, {
    "mode": "simple",
    "left": "{{nex.cleanCustomers.result.count}}",
    "operator": "GT",
    "right": "0",
})
no_customers = node(M, "FAILURE", "No Customers Found", 380, 750, {"response": {
    "message": "The API returned no customers",
}})
fork = node(M, "FORK", "Analyse In Parallel", 0, 750, {
    "branches": ["insights", "scoring"],
    "strategy": "WAIT_ALL",
    "onBranchFailure": "CONTINUE",
    "timeoutSeconds": 90,
    "onTimeout": "CONTINUE_WITH_PARTIAL",
})
ai = node(M, "AI", "Summarise Customers", -300, 900, {
    "provider": "GROQ",
    "model": "openai/gpt-oss-120b",
    "temperature": 0.2,
    "maxTokens": 1500,
    "prompt": (
        "You are a customer success analyst. Here are our customers as JSON "
        "(revenue in USD):\n{{nex.cleanCustomers.result.customers}}\n\n"
        "Total revenue is exactly ${{nex.cleanCustomers.result.totalRevenue}} USD "
        "(use this exact figure; it is not in thousands or millions). "
        "Write a one-line headline, a 2-3 sentence summary of who drives revenue, "
        "the city with the most revenue, and one concrete recommendation. "
        "Use plain ASCII punctuation only (normal hyphens and quotes)."
    ),
    "outputSchema": json.dumps({
        "headline": "string",
        "summary": "string",
        "topCity": "string",
        "recommendation": "string",
    }),
})
score = node(M, "SUB_FLOW", "Score Customers", 300, 900, {
    "targetFlowId": CHILD_ID,
    "targetFlowName": "Demo · Score Customers",
    "mode": "SYNC",
    "payload": {
        "customers": "{{nex.cleanCustomers.result.customers}}",
        "minRevenue": "{{nex.minRevenue}}",
    },
})
join = node(M, "JOIN", "Merge Results", 0, 1050)
loop = node(M, "LOOP", "Forecast Loop", 0, 1200, {
    "condition": "{{loop.index}} < {{nex.forecastWeeks}}",
    "maxIterations": 10,
    "saveOutputAs": "forecast",
})
project = node(M, "SCRIPT", "Project Week", -340, 1200, {
    "language": "javascript",
    "code": """// Runs once per loop pass: compounds revenue by the growth rate
const week = input.loop.iteration; // 1, 2, 3 ... ({{loop.index}} / input.loop.index are 0-based)
const prev = week > 1 ? nex.projectWeek?.result : null;
const projected = Math.round(nex.cleanCustomers.result.totalRevenue * Math.pow(nex.growthRate, week));
const previous = prev ? prev.lines : '';
return {
  weekNumber: week,
  week: 'Week ' + week,
  projectedRevenue: projected,
  lines: previous + '- Week ' + week + ': $' + projected.toLocaleString('en-US') + '\\n',
};""",
})
build = node(M, "MAPPER", "Build Report Data", 0, 1350, {"output": {
    "title": "{{nex.reportTitle}}",
    "customerCount": "{{nex.cleanCustomers.result.count}}",
    "totalRevenue": "{{nex.cleanCustomers.result.totalRevenue}}",
    "avgRevenue": "{{nex.cleanCustomers.result.avgRevenue}}",
    "headline": "{{nex.summariseCustomers.result.headline}}",
    "summary": "{{nex.summariseCustomers.result.summary}}",
    "topCity": "{{nex.summariseCustomers.result.topCity}}",
    "recommendation": "{{nex.summariseCustomers.result.recommendation}}",
    "scored": "{{nex.scoreCustomers.result.customers}}",
    "vipCount": "{{nex.scoreCustomers.result.vipCount}}",
    "topCustomer": "{{nex.scoreCustomers.result.topCustomer}}",
    "forecastWeeks": "{{nex.forecast.iterationCount}}",
    "forecastText": "{{nex.projectWeek.result.lines}}",
}})
sheet = node(M, "FILE", "Customer Spreadsheet", 0, 1500, {
    "operation": "WRITE",
    "format": "EXCEL",
    "fileName": "customer-report-{{meta.executionId}}",
    "data": "{{nex.buildReportData.scored}}",
    "columns": [
        {"header": "Customer", "path": "name"},
        {"header": "Email", "path": "email"},
        {"header": "City", "path": "city"},
        {"header": "Company", "path": "company"},
        {"header": "Orders", "path": "orders"},
        {"header": "Revenue (USD)", "path": "revenue"},
        {"header": "Tier", "path": "tier"},
    ],
})
pdf = node(M, "FILE", "Executive Summary", 0, 1650, {
    "operation": "WRITE",
    "format": "PDF",
    "fileName": "executive-summary-{{meta.executionId}}",
    "content": """# {{nex.buildReportData.title}}
Customers analysed: {{nex.buildReportData.customerCount}}
Total revenue: ${{nex.buildReportData.totalRevenue}} (average ${{nex.buildReportData.avgRevenue}} per customer)
VIP customers: {{nex.buildReportData.vipCount}} - top customer: {{nex.buildReportData.topCustomer}}

# AI insight
{{nex.buildReportData.headline}}
{{nex.buildReportData.summary}}
Top city: {{nex.buildReportData.topCity}}
Recommendation: {{nex.buildReportData.recommendation}}

# Revenue forecast ({{nex.buildReportData.forecastWeeks}} weeks at 8% growth)
{{nex.buildReportData.forecastText}}""",
})
verify = node(M, "FILE", "Verify Spreadsheet", 0, 1800, {
    "operation": "READ",
    "source": "{{nex.customerSpreadsheet}}",
    "parseAs": "EXCEL",
})
from_chat = node(M, "DECISION", "Started From Chat", 0, 1950, {
    "mode": "code",
    "language": "javascript",
    "code": "// True when a Slack / Telegram / WhatsApp / Discord message started this run\nreturn !!(nex.channel && nex.channel.platform);",
})
reply = node(M, "MESSAGE", "Reply With Report", -300, 2100, {
    "connectionId": "",
    "to": "",
    "text": (
        "Hi {{nex.message.from.name}} 👋 your {{nex.buildReportData.title}} is ready.\n"
        "{{nex.buildReportData.customerCount}} customers, ${{nex.buildReportData.totalRevenue}} revenue, "
        "{{nex.buildReportData.vipCount}} VIPs.\n{{nex.buildReportData.headline}}\n"
        "Spreadsheet: {{nex.customerSpreadsheet.downloadUrl}}"
    ),
    "file": "{{nex.executiveSummary}}",
    "replyInThread": True,
})
done = node(M, "SUCCESS", "Report Ready", 0, 2250, {"response": {
    "message": "Customer report generated",
    "customers": "{{nex.buildReportData.customerCount}}",
    "totalRevenue": "{{nex.buildReportData.totalRevenue}}",
    "vipCount": "{{nex.buildReportData.vipCount}}",
    "headline": "{{nex.buildReportData.headline}}",
    "forecast": "{{nex.forecast.accumulated}}",
    "spreadsheetRowsVerified": "{{nex.verifySpreadsheet.rowCount}}",
    "spreadsheetUrl": "{{nex.customerSpreadsheet.downloadUrl}}",
    "summaryPdfUrl": "{{nex.executiveSummary.downloadUrl}}",
}})

edge(M, start, settings)
edge(M, settings, fetch)
edge(M, fetch, clean, "SUCCESS", "success")
edge(M, fetch, api_down, "FAILURE", "failure")
edge(M, clean, any_customers, "SUCCESS", "success")
edge(M, clean, clean_failed, "FAILURE", "failure")
edge(M, any_customers, fork, "SUCCESS", "success")
edge(M, any_customers, no_customers, "FAILURE", "failure")
edge(M, fork, ai, "SUCCESS", "insights")
edge(M, fork, score, "SUCCESS", "scoring")
edge(M, ai, join, "SUCCESS", "success")
edge(M, score, join, "SUCCESS", "success")
edge(M, join, loop)
edge(M, loop, project, "CONTINUE", "continue")
edge(M, project, loop, "SUCCESS", "success")
edge(M, loop, build, "SUCCESS", "exit")
edge(M, build, sheet)
edge(M, sheet, pdf)
edge(M, pdf, verify)
edge(M, verify, from_chat)
edge(M, from_chat, reply, "SUCCESS", "success")
edge(M, from_chat, done, "FAILURE", "failure")
edge(M, reply, done)

# FORK keeps the entry node of each branch for the canvas.
for i, n in enumerate(nodes):
    if n[0] == fork:
        n[6]["branchNodeIds"] = {"insights": [ai], "scoring": [score]}


def q(s):
    return "'" + str(s).replace("'", "''") + "'"


out = ["BEGIN;",
       "DELETE FROM flow_edges WHERE flow_id IN (SELECT id FROM flows WHERE slug IN ('demo-customer-report','demo-score-customers'));",
       "DELETE FROM flow_nodes WHERE flow_id IN (SELECT id FROM flows WHERE slug IN ('demo-customer-report','demo-score-customers'));",
       "DELETE FROM flows WHERE slug IN ('demo-customer-report','demo-score-customers');"]
for fid, name, slug, desc in [
    (CHILD_ID, "Demo · Score Customers", "demo-score-customers",
     "Child flow used by the demo: tags each customer VIP / Gold / Standard"),
    (MAIN_ID, "Demo · Customer Health Report", "demo-customer-report",
     "Every node end to end: API → script → decision → fork (AI + sub-flow) → loop → Excel + PDF → chat reply"),
]:
    out.append(f"INSERT INTO flows (id,name,slug,description,user_id,status,created_at,updated_at) VALUES "
               f"({q(fid)},{q(name)},{q(slug)},{q(desc)},{q(USER_ID)},'ACTIVE',now(),now());")
for nid, fid, ntype, label, x, y, cfg in nodes:
    out.append(f"INSERT INTO flow_nodes (id,flow_id,node_type,label,config,position_x,position_y,created_at) VALUES "
               f"({q(nid)},{q(fid)},{q(ntype)},{q(label)},{q(json.dumps(cfg))}::jsonb,{x},{y},now());")
for eid, fid, src, dst, cond, handle in edges:
    h = q(handle) if handle else "NULL"
    out.append(f"INSERT INTO flow_edges (id,flow_id,source_node_id,target_node_id,condition_type,source_handle,created_at) VALUES "
               f"({q(eid)},{q(fid)},{q(src)},{q(dst)},{q(cond)},{h},now());")
out.append("COMMIT;")
print("\n".join(out))
print(f"-- MAIN={MAIN_ID} CHILD={CHILD_ID}", file=sys.stderr)
