import React, { useState } from 'react';

import Video from './Video';
import Poster from './Poster';

export default function EmbeddedVideo({
  video,
  title = 'Embedded youtube',
  className,
  format = 'webp',
  quality = 'maxresdefault',
}) {
  const [preconnect, setPreconnect] = useState(false);
  const [showIframe, setShowIframe] = useState(false);
  const posterVideoId = video.split('?')[0];
  const pathSegment = format === 'webp' ? 'vi_webp' : 'vi';
  const posterUrl = `https://i.ytimg.com/${pathSegment}/${posterVideoId}/${quality}.${format}`;

  const handlePreconnect = () => {
    if (preconnect) return;

    setPreconnect(true);
  };

  const handleShowIframe = () => {
    if (showIframe) return;

    setShowIframe(true);
  };

  return (
    <>
      <link rel="preload" href={posterUrl} as="image" />
      {preconnect ? (
        <>
          <link rel="preconnect" href="https://www.youtube.com" />
          <link rel="preconnect" href="https://www.google.com" />
        </>
      ) : null}

      {showIframe ? (
        <Video className={className} video={video} title={title} />
      ) : (
        <Poster
          className={className}
          posterUrl={posterUrl}
          onPointerOver={handlePreconnect}
          onClick={handleShowIframe}
        />
      )}
    </>
  );
}
