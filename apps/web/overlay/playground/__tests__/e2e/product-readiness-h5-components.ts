/** Inspect the actual pinned Wot roots; task text alone also renders for unresolved tags. */
export function inspectH5TaskComponents(task: Element) {
  const page = task.closest('.page');
  const buttons = [...(page?.querySelectorAll('uni-button.wd-button') ?? [])];
  const search = page?.querySelector('.search-card .wd-search');
  const field = search?.querySelector('.wd-search__field');
  // Wot 1.14.0 mounts its centered cover before activation, then the native input.
  const searchControl = field?.querySelector('.wd-search__cover .wd-icon, input');
  const tag = task.querySelector('.wd-tag');
  const visible = (element: Element | null | undefined) => {
    if (!element) return false;
    const style = getComputedStyle(element);
    const bounds = element.getBoundingClientRect();
    return style.display !== 'none' && style.visibility === 'visible'
      && Number(style.opacity) > 0 && bounds.width > 0 && bounds.height > 0;
  };
  const buttonContents = buttons.map(button => button.querySelector('.wd-button__content'));
  return {
    buttonsRendered: buttons.length >= 4 && buttons.every(visible)
      && buttonContents.every(visible),
    searchRendered: visible(search) && visible(field) && visible(searchControl),
    taskTagRendered: visible(tag) && visible(tag?.querySelector('.wd-tag__text')),
    stylesApplied: buttonContents.length >= 4 && buttonContents.every(content =>
      content && getComputedStyle(content).display === 'flex'
      && getComputedStyle(content).alignItems === 'center')
      && !!search && getComputedStyle(search).display === 'flex'
      && !!field && getComputedStyle(field).position === 'relative'
      && !!tag && getComputedStyle(tag).borderTopStyle === 'solid'
      && Number.parseFloat(getComputedStyle(tag).borderTopWidth) > 0,
    unresolvedTags: page?.querySelectorAll(
      'wd-button, wd-search, wd-tag, wd-input, wd-textarea, wd-icon',
    ).length ?? -1,
  };
}
