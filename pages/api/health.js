module.exports = function handler(req, res) {
  res.setHeader('Cache-Control', 'no-store');
  res.status(200).json({ ready: true, frontend: 'LiuJun-tao/Zepp-Life-Steps', login: 'miloce/Zepp-Life-Steps', loginCommit: '1a6c240', ...(process.env.ZEPP_DESKTOP_INSTANCE ? { desktopInstance: process.env.ZEPP_DESKTOP_INSTANCE } : {}) });
};
