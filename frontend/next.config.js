/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  output: 'standalone',

  images: {
    remotePatterns: [
      {
        protocol: 'http',
        hostname: 'localhost',
      },
    ],
    formats: ['image/avif', 'image/webp'],
  },

  compress: true,

  ...(process.env.NODE_ENV === 'production' && {
    experimental: {
      optimizePackageImports: ['lucide-react', 'recharts', 'date-fns'],
    },
  }),
};

module.exports = nextConfig;
