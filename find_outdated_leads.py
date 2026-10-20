import urllib.request
import urllib.parse
import json
import re
import ssl
import time
import os

API_KEY = "AIzaSyBb-cuFn1e7XGWBqJ9wwHOu9HBTZv5ZRH0"

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

HEADERS = {
    'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'
}

EXCLUDE_DOMAINS = [
    'yelp.com', 'tripadvisor.com', 'opentable.com', 'doordash.com',
    'grubhub.com', 'ubereats.com', 'facebook.com', 'instagram.com',
    'foursquare.com', 'zomato.com', 'toasttab.com', 'chownow.com',
    'singleplatform.com', 'resy.com', 'google.com', 'wikipedia.org',
    'yellowpages.com', 'menupix.com', 'allmenus.com', 'ezcater.com',
    'seamless.com', 'caviar.com', 'postmates.com', 'clover.com'
]

# Patterns for copyright 2023 or 2024
COPYRIGHT_PATTERN = re.compile(
    r'(?:©|&copy;|\(c\)|copyright)\s*(?:20)?(23|24)\b|'
    r'\b(2023|2024)\s*(?:©|&copy;|\(c\))\s*(?:copyright)?|'
    r'copyright\s+(?:[a-zA-Z0-9\s,\.-]{1,30})?\b(2023|2024)\b',
    re.IGNORECASE
)

# Year 2025/2026 check to ensure it's not actually updated to 2025 or 2026
UPDATED_YEAR_PATTERN = re.compile(
    r'(?:©|&copy;|\(c\)|copyright)\s*(?:20)?(25|26)\b|'
    r'\b(2025|2026)\s*(?:©|&copy;|\(c\))\s*(?:copyright)?',
    re.IGNORECASE
)

PHONE_PATTERN = re.compile(
    r'(?:\+?1[-.\s]?)?\(?([2-9]\d{2})\)?[-.\s]?([2-9]\d{2})[-.\s]?(\d{4})'
)
TEL_PATTERN = re.compile(r'href=[\'"]tel:([^\'"]+)[\'"]', re.IGNORECASE)

def search_places(query):
    url = "https://places.googleapis.com/v1/places:searchText"
    data = json.dumps({"textQuery": query}).encode('utf-8')
    req = urllib.request.Request(
        url,
        data=data,
        headers={
            "Content-Type": "application/json",
            "X-Goog-Api-Key": API_KEY,
            "X-Goog-FieldMask": "places.displayName,places.websiteUri,places.formattedAddress,places.nationalPhoneNumber,places.businessStatus,places.userRatingCount"
        }
    )
    try:
        with urllib.request.urlopen(req, context=ctx, timeout=15) as resp:
            res = json.loads(resp.read().decode('utf-8'))
            return res.get('places', [])
    except Exception as e:
        print(f"Error searching places for '{query}': {e}")
        return []

def check_website(url):
    domain = urllib.parse.urlparse(url).netloc.lower()
    if any(ex in domain for ex in EXCLUDE_DOMAINS):
        return None, []
        
    try:
        req = urllib.request.Request(url, headers=HEADERS)
        with urllib.request.urlopen(req, context=ctx, timeout=8) as resp:
            content_type = resp.headers.get('Content-Type', '')
            if 'text/html' not in content_type and 'text/plain' not in content_type and content_type != '':
                return None, []
            html = resp.read(500000).decode('utf-8', errors='ignore')
    except Exception:
        return None, []

    # If it has 2025 or 2026 copyright, it is not outdated
    if UPDATED_YEAR_PATTERN.search(html):
        return None, []

    # Check for copyright 2023 or 2024
    m = COPYRIGHT_PATTERN.search(html)
    if not m:
        return None, []
        
    year = m.group(1) or m.group(2) or m.group(3)
    if not year:
        return None, []
    if len(year) == 2:
        year = "20" + year

    # Extract phone numbers from website
    phones = []
    seen = set()
    for tel in TEL_PATTERN.findall(html):
        clean_tel = re.sub(r'[^\d+]', '', tel)
        if len(clean_tel) >= 10 and clean_tel not in seen:
            seen.add(clean_tel)
            phones.append(clean_tel)
            
    for pm in PHONE_PATTERN.findall(html):
        p_str = f"({pm[0]}) {pm[1]}-{pm[2]}"
        digits = pm[0] + pm[1] + pm[2]
        if digits not in seen:
            seen.add(digits)
            phones.append(p_str)
        
    return year, phones[:3]

queries = [
    # Rockville MD
    ("Rockville, MD", "cafes in Rockville MD"),
    ("Rockville, MD", "family restaurants in Rockville MD"),
    ("Rockville, MD", "italian restaurant Rockville MD"),
    ("Rockville, MD", "bistro cafe Rockville MD"),
    ("Rockville, MD", "bakery cafe Rockville MD"),
    ("Rockville, MD", "coffee shop Rockville MD"),
    ("Rockville, MD", "mexican restaurant Rockville MD"),
    ("Rockville, MD", "chinese restaurant Rockville MD"),
    ("Rockville, MD", "diner Rockville MD"),
    ("Rockville, MD", "pizza restaurant Rockville MD"),

    # Washington DC
    ("Washington, DC", "cafes in Georgetown Washington DC"),
    ("Washington, DC", "coffee shop Dupont Circle Washington DC"),
    ("Washington, DC", "restaurants in Adams Morgan Washington DC"),
    ("Washington, DC", "bakery cafe Washington DC"),
    ("Washington, DC", "family restaurant Capitol Hill DC"),
    ("Washington, DC", "bistro in Washington DC"),
    ("Washington, DC", "italian restaurant Washington DC"),
    ("Washington, DC", "cafes in Capitol Hill Washington DC"),
    ("Washington, DC", "restaurants in Columbia Heights DC"),
    ("Washington, DC", "diner Washington DC"),
    ("Washington, DC", "breakfast cafe Washington DC"),
    ("Washington, DC", "seafood restaurant Washington DC"),
    ("Washington, DC", "ethiopian restaurant Washington DC"),
    ("Washington, DC", "thai restaurant Washington DC"),

    # New York, NY
    ("New York, NY", "cafes in Brooklyn NY"),
    ("New York, NY", "restaurants in Astoria Queens NY"),
    ("New York, NY", "bakery cafe Manhattan NY"),
    ("New York, NY", "italian restaurant Staten Island NY"),
    ("New York, NY", "coffee shop Upper West Side Manhattan"),
    ("New York, NY", "bistro Greenwich Village NY"),
    ("New York, NY", "restaurants in Flushing Queens NY"),
    ("New York, NY", "family diner Brooklyn NY"),
    ("New York, NY", "cafes in Lower East Side Manhattan"),
    ("New York, NY", "pizza restaurant Bronx NY"),
    ("New York, NY", "restaurants in Williamsburg Brooklyn"),
    ("New York, NY", "coffee shop Harlem NY"),
    ("New York, NY", "diner Queens NY"),
    ("New York, NY", "cafes in East Village NY"),
    ("New York, NY", "mexican restaurant Brooklyn NY"),
    ("New York, NY", "restaurants in Midtown Manhattan"),
]

leads = []
seen_urls = set()
seen_names = set()

TARGET = 55  # aim for at least 50

print(f"Starting lead collection across Washington DC, Rockville MD, and New York...")

for location, q in queries:
    if len(leads) >= TARGET:
        break
    print(f"\n--- Searching: {q} ---")
    places = search_places(q)
    print(f"Found {len(places)} places from Google")
    
    for place in places:
        if len(leads) >= TARGET:
            break
            
        name = place.get('displayName', {}).get('text', '')
        website = place.get('websiteUri')
        phone = place.get('nationalPhoneNumber')
        address = place.get('formattedAddress', '')
        
        if not website:
            continue
            
        # Clean website URL
        clean_url = website.split('?')[0].rstrip('/')
        if clean_url in seen_urls or name in seen_names:
            continue
            
        year, extracted_phones = check_website(clean_url)
        if year:
            seen_urls.add(clean_url)
            seen_names.add(name)
            
            # Combine website extracted phone and Google Places phone
            final_phones = []
            if phone:
                final_phones.append(phone)
            for ep in extracted_phones:
                if ep not in final_phones:
                    final_phones.append(ep)
                    
            if not final_phones:
                continue
                
            lead_item = {
                "name": name,
                "location": location,
                "address": address,
                "website": clean_url,
                "copyrightYear": year,
                "phone": final_phones[0],
                "allPhones": final_phones
            }
            leads.append(lead_item)
            print(f"[{len(leads)}/{TARGET}] MATCH ({year}): {name} | Web: {clean_url} | Phone: {final_phones[0]}")
            
    time.sleep(0.5)

print(f"\n==========================================")
print(f"TOTAL LEADS FOUND WITH COPYRIGHT 2023/2024: {len(leads)}")
print(f"==========================================")

# Save JSON
with open('outdated_website_leads.json', 'w') as f:
    json.dump(leads, f, indent=2)

# Save Markdown report
with open('outdated_website_leads.md', 'w') as f:
    f.write("# ☕ Outdated Website Leads — Cafes & Restaurants with © 2023 / 2024\n\n")
    f.write(f"> **Criteria:** Active cafes & restaurants in **Washington, DC**, **Rockville, MD**, and **New York, NY** with live websites showing outdated copyright (**© 2023** or **© 2024**) and extracted contact phone numbers.\n")
    f.write(f"> **Total Verified Leads:** {len(leads)}\n\n")
    f.write("---\n\n")

    for loc in ["Rockville, MD", "Washington, DC", "New York, NY"]:
        loc_leads = [l for l in leads if l['location'] == loc]
        f.write(f"## 📍 {loc} ({len(loc_leads)} leads)\n\n")
        f.write("| # | Business Name | Website | Copyright | Phone Number | Address |\n")
        f.write("|---|---------------|---------|-----------|--------------|---------|\n")
        for idx, l in enumerate(loc_leads, 1):
            f.write(f"| {idx} | **{l['name']}** | [{l['website']}]({l['website']}) | `© {l['copyrightYear']}` | `{l['phone']}` | {l['address']} |\n")
        f.write("\n---\n\n")

print("Files generated: outdated_website_leads.json and outdated_website_leads.md")
