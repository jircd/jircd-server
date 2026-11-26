// @ts-check
import { defineConfig } from 'astro/config';
import mdx from '@astrojs/mdx';
import { unified } from '@astrojs/markdown-remark';
import starlight from '@astrojs/starlight';
import starlightLinksValidator from 'starlight-links-validator';
import rewriteMdLinks from './src/plugins/rewrite-md-links.mjs';

export default defineConfig({
  site: 'https://jircd.org',
  markdown: {
    processor: unified({ remarkPlugins: [rewriteMdLinks] }),
  },
  integrations: [
    starlight({
      title: 'JIRCD',
      description: 'A modular IRCv3 chat server written in Java, designed to be extended without touching its core.',
      logo: { light: './src/assets/logo.svg', dark: './src/assets/logo-dark.svg', alt: 'JIRCD' },
      social: [{ icon: 'github', label: 'GitHub', href: 'https://github.com/jircd/jircd-server' }],
      customCss: ['./src/styles/custom.css'],
      sidebar: [
        { label: 'User Guide', items: [{ autogenerate: { directory: 'user' } }] },
        { label: 'Administrator Guide', items: [{ autogenerate: { directory: 'admin' } }] },
        { label: 'Developer Guide', items: [{ autogenerate: { directory: 'developer' } }] },
        { label: 'API reference', link: '/api/', attrs: { target: '_blank' } },
      ],
      plugins: [
        starlightLinksValidator({
          // The Javadoc is copied into dist/api after this build; scripts/check-api-links.mjs checks those links.
          exclude: ['/api', '/api/**'],
        }),
      ],
    }),
    mdx(),
  ],
});
