/**
 * Integration test: parse a synthetic FastAPI repo structure.
 * Validates all 5 bug-class fixes from the spec.
 */
const { describe, it } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const os = require('os');
const { parseRepo } = require('../parser');

// Create a temp repo structure mimicking fastapi-realworld-example-app
function createTestRepo() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ts-fastapi-'));

  // requirements.txt
  fs.writeFileSync(path.join(root, 'requirements.txt'), 'fastapi==0.95.0\npydantic\nuvicorn\n');

  // app/api/routes/articles.py — tests: empty path, multi-line decorator, response_model
  const routesDir = path.join(root, 'app', 'api', 'routes');
  fs.mkdirSync(routesDir, { recursive: true });

  fs.writeFileSync(path.join(routesDir, 'articles.py'), `
from fastapi import APIRouter, Depends, Body
from app.models.domain.articles import Article

router = APIRouter(prefix="/articles", tags=["articles"])

@router.get(
    "",
    response_model=ArticleListResponse,
    status_code=200,
)
async def list_articles(
    limit: int = 20,
    offset: int = 0,
):
    """List all articles with pagination."""
    pass

@router.post("", response_model=ArticleResponse, status_code=201)
async def create_article(
    article: ArticleCreate = Body(...),
):
    """Create a new article."""
    pass

@router.get("/{slug}")
async def get_article(slug: str):
    """Get article by slug."""
    pass

@router.put("/{slug}", tags=["admin"])
async def update_article(slug: str, article: ArticleUpdate = Body(...)):
    """Update an existing article."""
    pass

@router.delete("/{slug}", status_code=204)
async def delete_article(slug: str):
    pass
`);

  // app/api/routes/profiles.py — tests: router without local prefix (external only)
  fs.writeFileSync(path.join(routesDir, 'profiles.py'), `
from fastapi import APIRouter

router = APIRouter(tags=["profiles"])

@router.get("/{username}")
async def get_profile(username: str):
    """Get user profile."""
    pass

@router.post("/{username}/follow")
async def follow_user(username: str):
    pass
`);

  // app/api/routes/tags.py — tests: route with no positional path arg
  fs.writeFileSync(path.join(routesDir, 'tags.py'), `
from fastapi import APIRouter

router = APIRouter(prefix="/tags", tags=["tags"])

@router.get(response_model=TagsResponse)
async def get_tags():
    """Get all tags."""
    pass
`);

  // app/main.py — tests: include_router with prefix
  fs.writeFileSync(path.join(root, 'app', 'main.py'), `
from fastapi import FastAPI
from app.api.routes import articles, profiles, tags

app = FastAPI()
app.include_router(articles.router, prefix="/api")
app.include_router(profiles.router, prefix="/api/profiles")
app.include_router(tags.router, prefix="/api")
`);

  // app/models/domain/articles.py — tests: Pydantic class with/without base class
  const modelsDir = path.join(root, 'app', 'models', 'domain');
  fs.mkdirSync(modelsDir, { recursive: true });

  fs.writeFileSync(path.join(modelsDir, 'articles.py'), `
from pydantic import BaseModel
from typing import Optional, List
from datetime import datetime

class ArticleCreate(BaseModel):
    title: str
    description: str
    body: str
    tag_list: List[str] = []

class ArticleUpdate(BaseModel):
    title: Optional[str] = None
    description: Optional[str] = None
    body: Optional[str] = None

class Profile:
    username: str
    bio: str
    image: str
`);

  return root;
}

describe('FastAPI tree-sitter parser', () => {
  let root;
  let result;

  it('parses the test repo', async () => {
    root = createTestRepo();
    result = await parseRepo(root);
    assert.ok(result);
    assert.ok(Array.isArray(result.apis));
    assert.ok(result.apis.length > 0, `Expected apis, got ${result.apis.length}`);
  });

  it('Bug 1: empty-string path @router.get("") works', async () => {
    if (!result) { root = createTestRepo(); result = await parseRepo(root); }
    const listArticles = result.apis.find(a => a.handler === 'list_articles');
    assert.ok(listArticles, 'list_articles route should be found');
    // With prefix /articles and external /api, path should be /api/articles
    assert.ok(listArticles.path.endsWith('/articles') || listArticles.path === '/api/articles',
      `Expected path ending /articles, got: ${listArticles.path}`);
  });

  it('Bug 2: multi-line decorator is captured correctly', async () => {
    if (!result) { root = createTestRepo(); result = await parseRepo(root); }
    const listArticles = result.apis.find(a => a.handler === 'list_articles');
    assert.ok(listArticles, 'multi-line decorated route should be found');
    assert.equal(listArticles.method, 'GET');
    assert.deepEqual(listArticles.statusCodes, [200]);
  });

  it('Bug 3: class without RWModel base still captured', async () => {
    if (!result) { root = createTestRepo(); result = await parseRepo(root); }
    // Profile class has no BaseModel parent — should still be indexed
    assert.ok(result.models['Profile'],
      'Profile class (no BaseModel) should be in model index');
  });

  it('extracts route path params structurally', async () => {
    if (!result) { root = createTestRepo(); result = await parseRepo(root); }
    const getArticle = result.apis.find(a => a.handler === 'get_article');
    assert.ok(getArticle, 'get_article route should be found');
    const slugParam = getArticle.parameters?.find(p => p.name === 'slug');
    assert.ok(slugParam, 'slug path param should be extracted');
    assert.equal(slugParam.location, 'PATH');
  });

  it('extracts response_model from decorator args', async () => {
    if (!result) { root = createTestRepo(); result = await parseRepo(root); }
    const listArticles = result.apis.find(a => a.handler === 'list_articles');
    assert.equal(listArticles?.responseBodyType, 'ArticleListResponse');
  });

  it('extracts docstrings', async () => {
    if (!result) { root = createTestRepo(); result = await parseRepo(root); }
    const getArticle = result.apis.find(a => a.handler === 'get_article');
    assert.equal(getArticle?.description, 'Get article by slug.');
  });

  it('extracts router-level tags', async () => {
    if (!result) { root = createTestRepo(); result = await parseRepo(root); }
    const getArticle = result.apis.find(a => a.handler === 'get_article');
    assert.deepEqual(getArticle?.tags, ['articles']);
  });

  it('route-level tags override router-level', async () => {
    if (!result) { root = createTestRepo(); result = await parseRepo(root); }
    const updateArticle = result.apis.find(a => a.handler === 'update_article');
    assert.deepEqual(updateArticle?.tags, ['admin']);
  });

  it('handles route with no positional path arg', async () => {
    if (!result) { root = createTestRepo(); result = await parseRepo(root); }
    const getTags = result.apis.find(a => a.handler === 'get_tags');
    assert.ok(getTags, 'get_tags route should be found');
    // Should use prefix as path: /api/tags
    assert.ok(getTags.path.includes('/tags'), `Expected /tags in path, got: ${getTags.path}`);
  });

  it('external prefix from include_router is applied', async () => {
    if (!result) { root = createTestRepo(); result = await parseRepo(root); }
    const getProfile = result.apis.find(a => a.handler === 'get_profile');
    assert.ok(getProfile, 'get_profile should be found');
    assert.ok(getProfile.path.startsWith('/api/profiles'),
      `Expected path starting /api/profiles, got: ${getProfile.path}`);
  });

  it('BODY params identified by model type', async () => {
    if (!result) { root = createTestRepo(); result = await parseRepo(root); }
    const createArticle = result.apis.find(a => a.handler === 'create_article');
    assert.ok(createArticle);
    assert.equal(createArticle.requestBodyType, 'ArticleCreate');
  });

  it('query params identified by primitive type', async () => {
    if (!result) { root = createTestRepo(); result = await parseRepo(root); }
    const listArticles = result.apis.find(a => a.handler === 'list_articles');
    const limitParam = listArticles?.parameters?.find(p => p.name === 'limit');
    assert.ok(limitParam, 'limit query param should be extracted');
    assert.equal(limitParam.location, 'QUERY');
  });
});
