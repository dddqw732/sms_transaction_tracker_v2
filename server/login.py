import asyncio
import os
from telethon import TelegramClient

async def main():
    print("=== Telethon Login Setup ===")
    print("Get your API_ID and API_HASH from https://my.telegram.org")
    
    api_id_input = input("Enter your API_ID: ").strip()
    if not api_id_input.isdigit():
        print("API_ID must be a number.")
        return
    api_id = int(api_id_input)
    api_hash = input("Enter your API_HASH: ").strip()
    phone = input("Enter your phone number (with country code, e.g. +252...): ").strip()
    
    print("\nConnecting to Telegram...")
    # 'telethon' is the session name, this will create a telethon.session file in the current directory.
    client = TelegramClient('telethon', api_id, api_hash)
    
    # client.start() automatically asks for the phone, then the login code, and optional 2FA password.
    await client.start(phone=phone)
    
    print("\n✅ Login successful! The 'telethon.session' file has been created.")
    print("You can now start the server!")
    
    # Save the ID and Hash to a config file so the server can reuse them
    with open(".env.telethon", "w") as f:
        f.write(f"TELEGRAM_API_ID={api_id}\n")
        f.write(f"TELEGRAM_API_HASH={api_hash}\n")

if __name__ == '__main__':
    asyncio.run(main())
