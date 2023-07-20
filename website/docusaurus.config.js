// @ts-check

const path = require("path")

const lightCodeTheme = require('prism-react-renderer/themes/github');
const darkCodeTheme = require('prism-react-renderer/themes/vsDark');

/** @type {import('@docusaurus/types').Config} */
const config = {
  title: 'ZIO',
  tagline: 'Type-safe, composable asynchronous and concurrent programming for Scala',
  url: 'https://zio.dev',
  baseUrl: '/',
  onBrokenLinks: 'warn',
  onBrokenMarkdownLinks: 'warn',
  favicon: 'img/favicon.png',
  organizationName: 'zio',
  projectName: 'zio',
  themeConfig: {
    image: 'https://zio.dev/img/zio.png',
    algolia: {
      // The application ID provided by Algolia
      appId: 'IAX8GRSWEQ',

      // Public API key: it is safe to commit it
      apiKey: '6d38dc1ca6f0305c6e883ef79f82523d',

      indexName: 'zio',

      // Optional: see doc section below
      contextualSearch: true,

      // Optional: path for search page that enabled by default (`false` to disable it)
      searchPagePath: 'search',
    },
    docs: {
      sidebar: {
        autoCollapseCategories: true,
      },
    },
    prism: {
      theme: lightCodeTheme,
      darkTheme: darkCodeTheme,
      additionalLanguages: ['json', 'java', 'scala']
    },
    navbar: {
      style: 'dark',
      logo: {
        alt: 'ZIO',
        src: '/img/navbar_brand.png',
      },
      items: [
        { type: 'doc', docId: 'overview/getting-started', label: 'Overview', position: 'left' },
        { type: 'doc', docId: 'reference/index', label: 'Reference', position: 'left' },
        { type: 'doc', docId: 'guides/index', label: 'Guides', position: 'left' },
        { type: 'doc', docId: 'ecosystem/index', label: 'Ecosystem', position: 'left' },
        { type: 'doc', docId: 'resources/index', label: 'Resources', position: 'left' },
        { type: 'doc', docId: 'events/index', label: 'Events', position: 'left' },
        { to: "http://chat.zio.dev", label: "Chat Bot", position: 'right' },
        { to: 'blog', label: 'Blog', position: 'right' },
        {
          href: 'https://github.com/zio/zio',
          position: 'right',
          className: 'header-github-link',
          'aria-label': 'GitHub repository',
        },
// Disabled extra link until the ZIO 1.x docs available
//        {
//          type: 'docsVersionDropdown',
//          position: 'right',
//          dropdownActiveClassDisabled: true,
//        }
      ],
    },
    footer: {
      style: 'dark',     links: [
        {
          title: 'Learn!',
          items: [
            {
              label: 'Getting Started!',
              href: '/overview/getting-started'
            },
            {
              label: 'Reference',
              href: '/reference'
            },
            {
              label: 'Guides',
              href: '/guides'
            },
            {
              label: 'Scaladoc of ZIO',
              href: 'https://javadoc.io/doc/dev.zio/zio_3/latest/index.html'
            }
          ],
        },
        {
          title: 'Community and Social',
          items: [
            {
              label: 'Github',
              href: 'https://github.com/zio/zio'
            },
            {
              label: 'Discord',
              href: 'https://discord.gg/2ccFBr4'
            },
            {
              label: 'Twitter',
              href: "https://twitter.com/zioscala"
            }
          ],
        },
        {
          title: 'ZIO Newsletter',
          items: [
            {
              html: `
                <a href="https://ziverge.us12.list-manage.com/subscribe?u=bac34bb9e79745552c9a22089&id=beb6f5996c" target="_blank" style="background-color:#e73c00;color:#fff;display:inline-block;font-size:14px;font-weight:bold;line-height:30px;text-align:center;text-decoration:none;width:100px;-webkit-text-size-adjust:none;border-radius: 10px; -moz-border-radius: 10px; -webkit-border-radius: 10px;">Subscribe</a>
              `
            }
          ],
        },
        {
          title: 'Contribution',
          items: [
            {
              label: 'Contributor Guidelines',
              href: '/contributor-guidelines'
            },
            {
              label: 'Contributing to The ZIO Documentation',
              href: '/contributing-to-documentation'
            },
            {
              label: 'Coding Guidelines',
              href: '/coding-guidelines'
            },
          ],
        },
        {
          title: 'More',
          items: [
            {
              label: 'Blog',
              href: "/blog"
            },
            {
              label: 'FAQ',
              href: '/faq'
            },
            {
              label: 'Adopters',
              href: '/adopters'
            },
            {
              label: 'Code of Conduct',
              href: '/code-of-conduct'
            }
          ],
        },
      ],
      copyright: `Copyright © ${new Date().getFullYear()} ZIO Maintainers - Built with <a href="https://v2.docusaurus.io/">Docusaurus v2</a>`,
    },
  },
  presets: [
    [
      '@docusaurus/preset-classic',
      {
        debug: true,
        theme: {
          customCss: [require.resolve('./src/css/custom.css')],
        },
        docs: {
          routeBasePath: '/',
          sidebarPath: require.resolve('./sidebars.js'),
          lastVersion: 'current',
          versions: {
            'current': {
              label: 'ZIO 2.x'
            },
          },
          remarkPlugins: [
            [require('blended-include-code-plugin'), { marker: 'CODE_INCLUDE' }],
            [require('remark-kroki-plugin'), { krokiBase: 'https://kroki.io', lang: "kroki", imgRefDir: "/img/kroki", imgDir: "static/img/kroki" }]
          ],
          editUrl: 'https://github.com/zio/zio/edit/series/2.x',
        },
        blog: {
          blogTitle: "ZIO Blog",
          blogDescription: "Stay Up-to-Date with ZIO and its Ecosystem!",
          postsPerPage: 'ALL',
        },
        googleAnalytics: {
          trackingID: 'UA-237088290-2',
          anonymizeIP: true,
        },
        gtag: {
          trackingID: 'G-SH0HNKLNRT',
          anonymizeIP: true,
        },
      },
    ],
  ],
  plugins: [
    [path.join(__dirname, './plugins/zio-ecosystem-docusaurus'), {}],
  ],
  markdown: {
    mermaid: true,
  },
  themes: ['@docusaurus/theme-mermaid'],
}

module.exports = config;

