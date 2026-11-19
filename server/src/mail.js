import nodemailer from 'nodemailer';
import { config } from './config.js';

const transport = config.smtpUrl ? nodemailer.createTransport(config.smtpUrl) : null;

export async function sendMail(to, subject, text) {
  if (!transport) {
    // Development fallback: print the email so links can be used locally.
    console.log(`\n[mail] to=${to}\n[mail] subject=${subject}\n${text}\n`);
    return;
  }
  await transport.sendMail({ from: config.mailFrom, to, subject, text });
}
