// `<fg-collection>` and `<fg-collection-item>`: a repeating group, one item per entry in a Fact
// Graph collection.
//
// Each item is a clone of the collection's `<template class="fg-collection__item-template">` with
// the item's id spliced into every abstract `/*/` path. See docs/internals/flow-runtime.md.

import { factGraph, saveFactGraph } from './fg-fact-graph.js'
import { configureCollectionIds, makeCollectionIdPath, generateUUID } from './fg-collection-utils.js'
import { checkCondition } from './fg-conditions.js'

class FgCollection extends HTMLElement {
  constructor () {
    super()

    // Held on the instance so disconnectedCallback can remove the same reference.
    this.addItemListener = () => this.addItem()

    this.boundUpdateAddItemButton = () => {
      if (this.getAddItemIfTruePath()) {
        this.updateAddItemButton()
      }
    }

    this.setCollectionItemNumbers = () => {
      const collectionItems = this.querySelectorAll('fg-collection-item')
      collectionItems.forEach((item, index) => {
        const itemNumberSlot = item.querySelectorAll('.collection-item-number')
        if (itemNumberSlot) {
          itemNumberSlot.forEach(slot => { slot.textContent = `#${index + 1}` })
        }
      })
    }
  }

  /** Flow `add-item-if-true`: the fact that must be true, or incomplete, to allow adding a row. */
  getAddItemIfTruePath () {
    const p = this.getAttribute('data-add-item-if-true')
    return p && p.length > 0 ? p : null
  }

  /** Flow `seed-item-if-true`: whether this collection starts with one empty row already open. */
  getSeedItemIfTruePath () {
    const p = this.getAttribute('data-seed-item-if-true')
    return p && p.length > 0 ? p : null
  }

  /** An incomplete fact keeps the button enabled, so a mid-edit answer does not block. */
  updateAddItemButton () {
    const factPath = this.getAddItemIfTruePath()
    if (!factPath) return
    const btn = this.querySelector('.fg-collection__add-item')
    if (!btn) return
    try {
      const r = factGraph.get(factPath)
      const allow = !r.hasValue || r.get === true
      btn.disabled = !allow
      btn.setAttribute('aria-disabled', String(!allow))
    } catch (e) {
      console.error(`Error updating collection add button (${factPath}):\n`, e)
      btn.disabled = false
      btn.setAttribute('aria-disabled', 'false')
    }
  }

  connectedCallback () {
    this.path = this.getAttribute('path')
    this.addItemButton = this.querySelector('.fg-collection__add-item')
    this.addItemButton.addEventListener('click', this.addItemListener)

    if (this.getAddItemIfTruePath()) {
      document.addEventListener('fg-update', this.boundUpdateAddItemButton)
    }

    const ids = factGraph.getCollectionIds(this.path)
    ids.map(id => this.addItem(id))

    if (this.getAttribute('disallowempty') === 'true' && this.querySelectorAll('fg-collection-item').length === 0) {
      this.addItem()
    }

    const seedPath = this.getSeedItemIfTruePath()
    if (seedPath && this.querySelectorAll('fg-collection-item').length === 0) {
      try {
        if (checkCondition(seedPath, 'isTrue')) {
          this.addItem()
        }
      } catch (e) {
        console.error(`Error seeding a default row from ${seedPath}:\n`, e)
      }
    }

    if (this.getAddItemIfTruePath()) {
      this.updateAddItemButton()
    }
  }

  disconnectedCallback () {
    if (this.getAddItemIfTruePath()) {
      document.removeEventListener('fg-update', this.boundUpdateAddItemButton)
    }
    this.addItemButton.removeEventListener('click', this.addItemListener)
  }

  addItem (id) {
    const addIfPath = this.getAddItemIfTruePath()
    if (!id && addIfPath) {
      try {
        const r = factGraph.get(addIfPath)
        if (r.hasValue && r.get === false) return
      } catch (e) {
        console.error('Error checking collection add-item-if-true fact:\n', e)
      }
    }

    const collectionId = id ?? generateUUID()

    if (!id) {
      factGraph.addToCollection(this.path, collectionId)
      saveFactGraph()
    }

    const collectionItem = document.createElement('fg-collection-item')
    collectionItem.setAttribute('collectionPath', this.path)
    collectionItem.setAttribute('collectionId', collectionId)
    const collectionItemsContainer = this.querySelector('.fg-collection__item-container')
    collectionItemsContainer.appendChild(collectionItem)
    const collectionItemButton = collectionItem.querySelector('summary')

    const detailsElement = collectionItem.querySelector('details')
    if (detailsElement) {
      detailsElement.open = true
    }

    collectionItemButton?.focus()
    document.dispatchEvent(new CustomEvent('fg-update'))
  }
}
customElements.define('fg-collection', FgCollection)

class FgCollectionItem extends HTMLElement {
  constructor () {
    super()

    // Held on the instance so disconnectedCallback can remove the same reference.
    this.clearListener = () => this.clear()
  }

  connectedCallback () {
    console.debug('Connecting', this)

    const fgCollection = this.closest('fg-collection')
    const templateContent = fgCollection.querySelector('.fg-collection__item-template').content.cloneNode(true)

    const collectionId = this.getAttribute('collectionId')
    configureCollectionIds(templateContent, collectionId)

    this.append(templateContent)

    const collectionItemButton = this.querySelector('.fg-collection__item-container summary')
    const collectionItemContent = this.querySelector('.fg-collection__item-container details')
    collectionItemButton.setAttribute('aria-controls', `collection-item-${collectionId}`)
    collectionItemContent.setAttribute('id', `collection-item-${collectionId}`)

    if (collectionItemContent.open === false) {
      collectionItemContent.open = true
    }

    this.removeButton = this.querySelector('.fg-collection-item__remove-item')
    const modalId = this.removeButton.getAttribute('for')
    this.clickRemoveItemListener = () => this.handleClickRemoveItem(modalId)
    this.removeButton.addEventListener('click', this.clickRemoveItemListener)

    document.addEventListener('fg-clear', this.clearListener)

    fgCollection.setCollectionItemNumbers()
  }

  disconnectedCallback () {
    console.debug('Disconnecting', this)

    this.removeButton.removeEventListener('click', this.clickRemoveItemListener)
    document.removeEventListener('fg-clear', this.clearListener)

    this.replaceChildren()
  }

  handleClickRemoveItem (modalId) {
    // One shared confirm modal per collection, so its confirm button is rebound to this item.
    const modal = document.querySelector(`#${modalId}`)
    const confirmButton = modal.querySelector('.fg-collection__remove-item-modal__button-confirm')
    confirmButton.onclick = () => {
      const fgCollection = this.closest('fg-collection')
      const addButton = fgCollection.querySelector('.fg-collection__add-item')

      this.clear()
      addButton.focus()
    }
  }

  clear () {
    for (const fgSet of this.querySelectorAll('fg-set')) {
      fgSet.remove()
    }

    const fgCollection = this.closest('fg-collection')
    const collectionPath = this.getAttribute('collectionPath')
    const collectionId = this.getAttribute('collectionId')
    factGraph.delete(makeCollectionIdPath(`${collectionPath}/*`, collectionId))
    factGraph.removeFromCollection(collectionPath, collectionId)
    saveFactGraph()

    this.remove()
    fgCollection.setCollectionItemNumbers()
    document.dispatchEvent(new CustomEvent('fg-update'))
  }
}
customElements.define('fg-collection-item', FgCollectionItem)
