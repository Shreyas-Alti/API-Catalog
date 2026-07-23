const BASE_URL = 'http://localhost:8080/api'

export interface ApiParameter {
  name: string | null
  type: string | null
  location: string // PATH | QUERY | HEADER | BODY | COOKIE
  required: boolean
  validations: string[] | null
  description: string | null
}

export interface ApiField {
  name: string
  type: string | null
  validations: string[] | null
  description: string | null
}

export interface ExtractedApi {
  method: string
  path: string
  controller: string | null
  handler: string | null
  description: string | null
  tags: string[] | null
  parameters: ApiParameter[] | null
  requestBodyType: string | null
  requestBodyFields: ApiField[] | null
  responseBodyType: string | null
  responseBodyFields: ApiField[] | null
  statusCodes: number[] | null
  sourceFile: string | null
  sourceLine: number | null
  // AI-enriched
  summary: string | null
  requestExample: Record<string, unknown> | null
  responseExample: Record<string, unknown> | null
  aiGenerated: boolean
  needsReview: boolean
  llmModel: string | null
  manuallyEdited: boolean
}

export interface SubmitResponse {
  name: string
  url: string
  hostUrl: string | null
  framework: string
  supported: boolean
  apis: ExtractedApi[]
  commitSha: string | null
}

export interface SaveRequest {
  url: string
  hostUrl: string | null
  name: string
  framework: string
  apis: ExtractedApi[]
  commitSha: string | null
}

export interface RepositorySummary {
  id: number
  name: string
  url: string
  hostUrl: string | null
  framework: string
  endpointCount: number
  createdAt: string
}

export interface EndpointDetail {
  id: number
  method: string
  path: string
  description: string | null
  controller: string | null
  handler: string | null
  tags: string[] | null
  parameters: ApiParameter[] | null
  requestBodyType: string | null
  requestBodyFields: ApiField[] | null
  responseBodyType: string | null
  responseBodyFields: ApiField[] | null
  statusCodes: number[] | null
  sourceFile: string | null
  sourceLine: number | null
  summary: string | null
  requestExample: Record<string, unknown> | null
  responseExample: Record<string, unknown> | null
  aiGenerated: boolean
  needsReview: boolean
  llmModel: string | null
  manuallyEdited: boolean
}

export interface RepositoryDetail {
  id: number
  name: string
  url: string
  hostUrl: string | null
  framework: string
  createdAt: string
  endpoints: EndpointDetail[]
}

async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE_URL}${path}`, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  })
  if (!res.ok) {
    const err = await res.json().catch(() => ({ message: `Request failed (${res.status})` }))
    throw new Error(err.message || `Error ${res.status}`)
  }
  return res.json()
}

export const submitRepository = (url: string, hostUrl: string | null = null) =>
  request<SubmitResponse>('/repositories/submit', { method: 'POST', body: JSON.stringify({ url, hostUrl }) })

export const saveRepository = (data: SaveRequest) =>
  request<RepositoryDetail>('/repositories/save', { method: 'POST', body: JSON.stringify(data) })

export const getRepositories = () =>
  request<RepositorySummary[]>('/repositories')

export const getRepository = (id: number) =>
  request<RepositoryDetail>(`/repositories/${id}`)

export const deleteRepository = (id: number) =>
  fetch(`http://localhost:8080/api/repositories/${id}`, { method: 'DELETE' })
    .then(res => { if (!res.ok) throw new Error(`Delete failed (${res.status})`) })

export const rescanRepository = (id: number) =>
  request<RepositoryDetail>(`/repositories/${id}/rescan`, { method: 'POST' })

export const regenerateEndpoint = (id: number, force = false) =>
  fetch(`${BASE_URL}/endpoints/${id}/regenerate?force=${force}`, { method: 'POST' })
    .then(res => { if (!res.ok && res.status !== 204) throw new Error(`Regenerate failed (${res.status})`) })

export interface EndpointPatch {
  method?: string
  path?: string
  description?: string
  summary?: string
  tags?: string[]
  requestBodyType?: string
  responseBodyType?: string
}

export const patchEndpoint = (id: number, patch: EndpointPatch) =>
  fetch(`${BASE_URL}/endpoints/${id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch),
  }).then(res => { if (!res.ok) throw new Error(`Patch failed (${res.status})`) })

export interface SearchResultItem {
  endpointId: number
  method: string
  path: string
  description: string | null
  controller: string | null
  handler: string | null
  repositoryId: number
  repositoryName: string
  repositoryUrl: string
  framework: string
}

export const searchEndpoints = (params: {
  repo?: string
  framework?: string
  method?: string
  path?: string
}) => {
  const qs = new URLSearchParams()
  if (params.repo)      qs.set('repo',      params.repo)
  if (params.framework) qs.set('framework', params.framework)
  if (params.method)    qs.set('method',    params.method)
  if (params.path)      qs.set('path',      params.path)
  return request<SearchResultItem[]>(`/search?${qs}`)
}

