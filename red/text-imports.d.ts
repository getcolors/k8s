declare module "*.tf" { const content: string; export default content; }
declare module "*.yml" { const content: string; export default content; }
declare module "*.yaml" { const content: string; export default content; }
declare module "*.cfg" { const content: string; export default content; }
declare module "*.ini" { const content: string; export default content; }
declare module "*.sh" { const content: string; export default content; }
// ONCE's red tools import its own template tree by extension-less path; the
// ONCE dependency brings those imports onto this typecheck.
declare module "*/authorized-keys" { const content: string; export default content; }
declare module "*/deploy" { const content: string; export default content; }
declare module "*/once" { const content: string; export default content; }
