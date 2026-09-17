/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  // Desktop builds use a separate directory so the running local site is untouched.
  ...(process.env.ZEPP_DESKTOP_BUILD === '1' ? { output: 'standalone', distDir: '.next-desktop' } : {}),
}

module.exports = nextConfig
