/** Amend the pinned Vite pipeline, not the generated pages.json or each page. */
export function configureUnibestWot(source) {
  const componentImport = "import UniComponents from '@uni-helper/vite-plugin-uni-components'";
  const componentCall = '      UniComponents({\n';
  const resolverImport = "import { WotResolver } from '@uni-helper/vite-plugin-uni-components/resolvers'";
  if (typeof source !== 'string'
      || source.split(componentImport).length !== 2
      || source.split(componentCall).length !== 2
      || source.includes('WotResolver')
      || source.indexOf(componentCall) > source.indexOf('      Uni(),')) {
    throw new Error('PINNED_UNIBEST_COMPONENT_PIPELINE_CHANGED');
  }
  // The existing dependency already exports this resolver. Resolve Wd* components
  // at compile time instead of leaving inert <wd-input>/<wd-button> HTML tags.
  return source.replace(componentImport, componentImport + '\n' + resolverImport)
    .replace(componentCall, componentCall + '        resolvers: [WotResolver()],\n');
}
