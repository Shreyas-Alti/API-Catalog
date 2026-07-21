/**
 * Derives a business capability group name for an endpoint.
 *
 * Priority order:
 *   1. First OpenAPI @Tag
 *   2. Controller / router name (stripped of common suffixes)
 *   3. First meaningful path segment
 *   4. "Other"
 */
export function getGroup(ep: {
  tags?: string[] | null
  controller?: string | null
  path?: string | null
}): string {
  // 1. OpenAPI tag
  if (ep.tags && ep.tags.length > 0) return ep.tags[0]

  // 2. Controller name — strip common Java/Spring/NestJS suffixes
  if (ep.controller) {
    return ep.controller
      .replace(/Controller$|Resource$|Handler$|RestController$|Rest$|Api$/i, '')
      .replace(/([A-Z])/g, ' $1')  // camelCase → Title Case
      .trim()
      || ep.controller
  }

  // 3. First meaningful path segment (skip version prefixes like /v1, /api)
  if (ep.path) {
    const segments = ep.path
      .split('/')
      .filter(s => s && !/^v\d+$/i.test(s) && !/^api$/i.test(s) && !s.startsWith('{'))
    if (segments.length > 0) return segments[0]
  }

  return 'Other'
}

/** Returns an emoji for a capability group name based on common keywords. */
export function getGroupIcon(name: string): string {
  const l = name.toLowerCase()
  if (/user|auth|login|account|profile|member|session/.test(l)) return '👤'
  if (/product|item|catalog|inventory|sku|good/.test(l))        return '📦'
  if (/order|cart|basket|checkout|purchase/.test(l))            return '🛒'
  if (/payment|billing|invoice|refund|transaction/.test(l))     return '💳'
  if (/report|analytic|stat|metric|insight/.test(l))            return '📊'
  if (/admin|manage|setting|config|system/.test(l))             return '⚙️'
  if (/notif|message|email|alert|inbox/.test(l))                return '🔔'
  if (/file|upload|media|image|document/.test(l))               return '📎'
  if (/search|filter|find|query/.test(l))                       return '🔍'
  if (/health|status|ping|monitor/.test(l))                     return '💚'
  if (/post|article|blog|content/.test(l))                      return '📝'
  if (/category|tag|label|group/.test(l))                       return '🏷️'
  return '📁'
}

/** Groups a list of endpoints (or any items with tags/controller/path) by capability. */
export function groupByCapability<T extends {
  tags?: string[] | null
  controller?: string | null
  path?: string | null
}>(items: T[]): Map<string, T[]> {
  const groups = new Map<string, T[]>()
  for (const item of items) {
    const key = getGroup(item)
    if (!groups.has(key)) groups.set(key, [])
    groups.get(key)!.push(item)
  }
  return groups
}
