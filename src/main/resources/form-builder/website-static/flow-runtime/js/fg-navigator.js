// Single-question-per-screen navigation: the show and hide that would happen inside a page happens
// between pages instead.
//
// Reads resources/flow-manifest.json, which only `--singleQuestionPerScreen` emits, and no-ops
// without it. See docs/internals/flow-runtime.md.

import { checkCondition } from './fg-conditions.js'
import { appBasePath, resourceUrl } from './runtime-paths.js'

async function loadFlowManifest () {
  try {
    const res = await fetch(resourceUrl('flow-manifest.json'))
    if (!res.ok) return null
    return await res.json()
  } catch (e) {
    return null
  }
}

function stripAppPrefix (pathname) {
  const prefix = appBasePath()
  return prefix && pathname.startsWith(prefix) ? pathname.slice(prefix.length) : pathname
}

/** Read off `<html lang>`, which the generator already stamps, rather than matched against a list. */
function detectLocaleSegment (pathAfterApp) {
  const lang = document.documentElement.lang
  if (!lang || lang === 'en') return ''
  const firstSegment = pathAfterApp.startsWith('/') ? pathAfterApp.slice(1).split('/')[0] : ''
  return firstSegment === lang ? lang : ''
}

function currentLangPrefix () {
  const afterApp = stripAppPrefix(window.location.pathname)
  const locale = detectLocaleSegment(afterApp)
  return locale ? `/${locale}` : ''
}

function currentRouteFromLocation () {
  let path = stripAppPrefix(window.location.pathname)
  const locale = detectLocaleSegment(path)
  if (locale) path = path.slice(locale.length + 1) // remove "/{locale}"
  if (path === '' || path === '/') return '/'
  return path.endsWith('/') ? path.slice(0, -1) : path
}

function hrefForRoute (route) {
  const lang = currentLangPrefix()
  const routePortion = route === '/' ? '/' : `${route}/`
  return `${appBasePath()}${lang}${routePortion}`
}

function pageGateLive (manifestEntry) {
  if (!manifestEntry || !manifestEntry.gatePath || !manifestEntry.gateOperator) return true
  return checkCondition(manifestEntry.gatePath, manifestEntry.gateOperator)
}

function findLive (manifest, fromIdx, step) {
  for (let i = fromIdx + step; i >= 0 && i < manifest.length; i += step) {
    if (pageGateLive(manifest.at(i))) return i
  }
  return -1
}

export function refreshNavigationLinks (manifest) {
  const currentRoute = currentRouteFromLocation()
  const currentIdx = manifest.findIndex(p => p.route === currentRoute)
  if (currentIdx < 0) return

  const nextLink = document.querySelector('.form-actions a.usa-button:not(.usa-button--outline)')
  if (nextLink) {
    const nextIdx = findLive(manifest, currentIdx, 1)
    const nextEntry = nextIdx >= 0 ? manifest.at(nextIdx) : null
    if (nextEntry) nextLink.href = hrefForRoute(nextEntry.route)
  }

  const backLinks = document.querySelectorAll('.form-actions a.usa-button--outline')
  if (backLinks.length > 0) {
    const prevIdx = findLive(manifest, currentIdx, -1)
    const prevEntry = prevIdx >= 0 ? manifest.at(prevIdx) : null
    if (prevEntry) {
      const newHref = hrefForRoute(prevEntry.route)
      for (const link of backLinks) link.href = newHref
    }
  }
}

export async function initSingleQuestionNav () {
  const manifest = await loadFlowManifest()
  if (!manifest) return

  const currentRoute = currentRouteFromLocation()
  const currentIdx = manifest.findIndex(p => p.route === currentRoute)
  const currentEntry = currentIdx >= 0 ? manifest.at(currentIdx) : null
  if (currentEntry && !pageGateLive(currentEntry)) {
    // This page should be skipped given the current answers, so bounce forward to the first live one.
    const nextIdx = findLive(manifest, currentIdx, 1)
    const nextEntry = nextIdx >= 0 ? manifest.at(nextIdx) : null
    if (nextEntry) {
      window.location.replace(hrefForRoute(nextEntry.route))
      return
    }
  }

  refreshNavigationLinks(manifest)
  document.addEventListener('fg-update', () => refreshNavigationLinks(manifest))
}
