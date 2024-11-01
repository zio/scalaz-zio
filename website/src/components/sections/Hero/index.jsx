import React from 'react';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import Link from '@docusaurus/Link';
import { FaArrowRight } from 'react-icons/fa6';

import EmbeddedVideo from '@site/src/components/EmbeddedVideo';

export default function Hero() {
  const context = useDocusaurusContext();
  const { siteConfig = {} } = context;

  return (
    <section className="flex flex-col gap-24 py-20">
      <div className="container flex flex-col items-center gap-6">
        <img
          className="h-20"
          src="/img/navbar_brand2x.png"
          alt={`${siteConfig.title}`}
        />

        <p className="w-full max-w-7xl text-center text-3xl font-black leading-tight md:text-5xl">
          Type-safe, composable asynchronous and concurrent programming for{' '}
          <span className="text-primary">Scala</span>
        </p>

        <Link
          className="hover:bg-primary-500 bg-primary flex items-center gap-2 rounded-full px-6 py-2 font-semibold text-white hover:text-white hover:no-underline"
          to="/overview/getting-started"
        >
          <span>Get Started</span>
          <FaArrowRight />
        </Link>
      </div>

      <div className="container">
        <EmbeddedVideo
          className="mx-auto w-full max-w-7xl"
          video="XUwynbWUlhg"
        />
      </div>
    </section>
  );
}
