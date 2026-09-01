const buttonSelector = 'uni-button.wd-button'

let installed = false
let observer: MutationObserver | undefined

function disabled(button: HTMLElement) {
  return button.classList.contains('is-disabled')
    || button.hasAttribute('disabled')
    || button.getAttribute('aria-disabled') === 'true'
}

function normalizeButton(button: HTMLElement) {
  const isDisabled = disabled(button)
  const name = button.getAttribute('aria-label')
    || button.textContent?.replace(/\s+/gu, ' ').trim()
    || ''

  button.setAttribute('role', 'button')
  button.setAttribute('tabindex', isDisabled ? '-1' : '0')
  button.setAttribute('aria-disabled', String(isDisabled))
  if (name) button.setAttribute('aria-label', name)
}

function normalizeTree(root: ParentNode) {
  if (root instanceof HTMLElement && root.matches(buttonSelector)) {
    normalizeButton(root)
  }
  root.querySelectorAll<HTMLElement>(buttonSelector).forEach(normalizeButton)
}

function startObserver() {
  document.documentElement.lang = 'zh-CN'
  normalizeTree(document)
  observer = new MutationObserver((records) => {
    for (const record of records) {
      if (record.type === 'attributes' && record.target instanceof HTMLElement) {
        if (record.target.matches(buttonSelector)) normalizeButton(record.target)
        continue
      }
      record.addedNodes.forEach((node) => {
        if (node instanceof HTMLElement) normalizeTree(node)
      })
    }
  })
  observer.observe(document.documentElement, {
    attributeFilter: ['class', 'disabled'],
    attributes: true,
    childList: true,
    subtree: true,
  })
}

function activateFromKeyboard(event: KeyboardEvent) {
  if (event.repeat || (event.key !== 'Enter' && event.key !== ' ')) return
  const target = event.target
  if (!(target instanceof HTMLElement)) return
  const button = target.closest<HTMLElement>(buttonSelector)
  if (!button || disabled(button)) return
  event.preventDefault()
  button.click()
}

export function installH5Accessibility() {
  if (installed || typeof document === 'undefined') return
  installed = true
  document.addEventListener('keydown', activateFromKeyboard, true)
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', startObserver, { once: true })
  }
  else {
    startObserver()
  }
}

export function uninstallH5Accessibility() {
  if (!installed || typeof document === 'undefined') return
  installed = false
  observer?.disconnect()
  observer = undefined
  document.removeEventListener('keydown', activateFromKeyboard, true)
}
