/**
 * End-to-end test: invoke the CLI via stdin/stdout the same way the Java backend does.
 */
const { execSync } = require('child_process');
const fs = require('fs');
const path = require('path');
const os = require('os');

// Create a mini FastAPI repo
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'e2e-'));
fs.writeFileSync(path.join(root, 'requirements.txt'), 'fastapi\n');
fs.mkdirSync(path.join(root, 'app'), { recursive: true });
fs.writeFileSync(path.join(root, 'app', 'routes.py'), `
from fastapi import APIRouter

router = APIRouter(prefix="/users", tags=["users"])

@router.get("")
async def list_users():
    """List all users."""
    pass

@router.get("/{user_id}")
async def get_user(user_id: int):
    pass

@router.post("")
async def create_user(name: str, email: str):
    pass
`);

const input = JSON.stringify({ rootPath: root });
const cwd = path.resolve(__dirname, '..', '..');

const result = execSync(`node src/index.js`, {
  cwd,
  input,
  encoding: 'utf8',
  timeout: 10000,
});

const parsed = JSON.parse(result);
console.log(`Extracted ${parsed.apis.length} endpoints:`);
for (const api of parsed.apis) {
  const params = (api.parameters || []).map(p => `${p.name}:${p.location}`).join(', ');
  console.log(`  ${api.method} ${api.path} → ${api.handler}(${params})`);
}

// Assertions
const assert = require('assert/strict');
assert.equal(parsed.apis.length, 3);
assert.equal(parsed.apis[0].path, '/users');
assert.equal(parsed.apis[1].path, '/users/{user_id}');
assert.equal(parsed.apis[1].parameters[0].name, 'user_id');
assert.equal(parsed.apis[1].parameters[0].location, 'PATH');

console.log('\n✓ End-to-end CLI test passed');
