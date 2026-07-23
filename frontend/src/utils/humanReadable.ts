import type { ExtractedApi } from '../api/client'
import type { EndpointDetail, SearchResultItem } from '../api/client'

/**
 * Converts a camelCase or snake_case handler/function name to a readable title.
 * getUserById  → "Get User By Id"
 * create_order → "Create Order"
 * deleteAccount → "Delete Account"
 */
export function toHumanReadable(name: string | null | undefined): string | null {
  if (!name) return null
  const spaced = name
    .replace(/([A-Z])/g, ' $1')   // camelCase → spaced
    .replace(/[_-]+/g, ' ')        // snake_case → spaced
    .replace(/\s+/g, ' ')
    .trim()
  if (!spaced) return null
  return spaced
    .split(' ')
    .filter(w => w.length > 0)
    .map(w => w.charAt(0).toUpperCase() + w.slice(1).toLowerCase())
    .join(' ')
}

/** Returns the best available human label for an endpoint. */
export function endpointLabel(ep: {
  description?: string | null
  handler?: string | null
  path?: string | null
}): string | null {
  if (ep.description && ep.description.trim()) return ep.description.trim()
  return toHumanReadable(ep.handler)
}

type CopyableEndpoint = Partial<ExtractedApi> & Partial<EndpointDetail> & {
  method?: string
  path?: string
}

/** Formats an endpoint as plain text for pasting into messages, tickets, or emails. */
export function formatEndpointAsText(ep: CopyableEndpoint): string {
  const lines: string[] = []

  const title = endpointLabel(ep)
  lines.push(`${ep.method ?? '?'}  ${ep.path ?? '/'}`)
  if (title) lines.push(title)

  if (ep.description) {
    lines.push('')
    lines.push(ep.description)
  }

  const params = ep.parameters?.filter(p => p.location !== 'BODY') ?? []
  if (params.length > 0) {
    lines.push('')
    lines.push('Parameters:')
    for (const p of params) {
      const req = p.required ? 'required' : 'optional'
      lines.push(`  • ${p.name ?? '?'} (${p.location}, ${req})${p.type ? ': ' + p.type : ''}`)
    }
  }

  if (ep.requestBodyType) {
    lines.push('')
    lines.push(`Request Body: ${ep.requestBodyType}`)
    if (ep.requestBodyFields?.length) {
      for (const f of ep.requestBodyFields) {
        const v = f.validations?.length ? `  [${f.validations.join(' ')}]` : ''
        lines.push(`  • ${f.name}: ${f.type ?? '?'}${v}`)
      }
    }
  }

  if (ep.responseBodyType) {
    lines.push('')
    lines.push(`Response: ${ep.responseBodyType}`)
    if (ep.responseBodyFields?.length) {
      for (const f of ep.responseBodyFields) {
        lines.push(`  • ${f.name}: ${f.type ?? '?'}`)
      }
    }
  }

  if (ep.statusCodes?.length) {
    lines.push('')
    lines.push(`Status Codes: ${ep.statusCodes.join(', ')}`)
  }

  if (ep.tags?.length) lines.push(`Tags: ${ep.tags.join(', ')}`)

  if (ep.sourceFile) {
    lines.push(`Source: ${ep.sourceFile}${ep.sourceLine ? ':' + ep.sourceLine : ''}`)
  }

  return lines.join('\n')
}

/**
 * Converts a SearchResultItem (flat, no rich detail) to an EndpointDetail shape
 * so EndpointCard can render it before full details are lazy-loaded.
 */
export function toBasicDetail(sr: SearchResultItem): EndpointDetail {
  return {
    id: sr.endpointId,
    method: sr.method,
    path: sr.path,
    description: sr.description,
    controller: sr.controller,
    handler: sr.handler,
    tags: null,
    parameters: null,
    requestBodyType: null,
    requestBodyFields: null,
    responseBodyType: null,
    responseBodyFields: null,
    statusCodes: null,
    sourceFile: null,
    sourceLine: null,
    summary: null,
    requestExample: null,
    responseExample: null,
    aiGenerated: false,
    needsReview: false,
    llmModel: null,
    manuallyEdited: false,
  }
}
