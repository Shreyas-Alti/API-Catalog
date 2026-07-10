const BASE_URL = 'http://localhost:8080/api'

export interface ExtractedApi {
  method: string
  path: string
  description: string | null
  controller: string | null
  handler: string | null
  parameters: string[] | null
  requestBody: string | null
  responseBody: string | null
  statusCodes: number[] | null
}

export interface SubmitResponse {
  name: string
  url: string
  framework: string
  supported: boolean
  apis: ExtractedApi[]
}

export interface SaveRequest {
  url: string
  name: string
  framework: string
  apis: ExtractedApi[]
}

export interface RepositorySummary {
  id: number
  name: string
  url: string
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
  parameters: string[] | null
  requestBody: string | null
  responseBody: string | null
  statusCodes: number[] | null
}

export interface RepositoryDetail {
  id: number
  name: string
  url: string
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

export const submitRepository = (url: string) =>
  request<SubmitResponse>('/repositories/submit', { method: 'POST', body: JSON.stringify({ url }) })

export const saveRepository = (data: SaveRequest) =>
  request<RepositoryDetail>('/repositories/save', { method: 'POST', body: JSON.stringify(data) })

export const getRepositories = () =>
  request<RepositorySummary[]>('/repositories')

export const getRepository = (id: number) =>
  request<RepositoryDetail>(`/repositories/${id}`)

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

