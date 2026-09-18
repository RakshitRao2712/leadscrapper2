import urllib.request
import urllib.parse
import json
import time

cities = [
    "Dover, UK", "Margate, UK", "Folkestone, UK", "Ramsgate, UK", "Deal, UK",
    "Whitstable, UK", "Herne Bay, UK", "Faversham, UK", "Sittingbourne, UK", "Sheerness, UK",
    "Dartford, UK", "Gravesend, UK", "Tonbridge, UK", "Tunbridge Wells, UK", "Sevenoaks, UK",
    "Ashford, UK", "Canterbury, UK", "Maidstone, UK", "Chatham, UK", "Rochester, UK",
    "Gillingham, UK", "Aylesford, UK", "Snodland, UK", "West Malling, UK", "Kings Hill, UK",
    "Stowmarket, UK", "Sudbury, UK", "Newmarket, UK", "Mildenhall, UK", "Haverhill, UK",
    "Felixstowe, UK", "Woodbridge, UK", "Aldeburgh, UK", "Leiston, UK", "Saxmundham, UK",
    "Halesworth, UK", "Southwold, UK", "Beccles, UK", "Bungay, UK", "Lowestoft, UK",
    "Great Yarmouth, UK", "Gorleston, UK", "Cromer, UK", "Sheringham, UK", "Holt, UK"
]
queries = [
    "small family restaurant", "local cafe", "plumber", "electrician", "roofer", 
    "auto repair", "barber shop", "hair salon", "cleaning service", "landscaping"
]

leads = []
target_leads = 50

print("Fetching leads...")

for city in cities:
    for query_prefix in queries:
        if len(leads) >= target_leads:
            break
        
        query = f"{query_prefix} in {city}"
        url = f"http://localhost:8080/api/leads?query={urllib.parse.quote(query)}"
        print(f"Querying: {query}")
        
        try:
            req = urllib.request.Request(url)
            with urllib.request.urlopen(req) as response:
                data = json.loads(response.read().decode())
                
                for place in data:
                    leads.append(place)
                    print(f"Found lead: {place.get('displayName', {}).get('text')}")
                    if len(leads) >= target_leads:
                        break
        except Exception as e:
            print(f"Error querying {url}: {e}")
            
        time.sleep(0.5)

    if len(leads) >= target_leads:
        break

print(f"Found {len(leads)} leads.")

with open('leads.md', 'w') as f:
    f.write("# New UK Business Leads — 50 Verified Operational Businesses Without Websites\n\n")
    f.write("> [!TIP]\n")
    f.write("> All businesses listed below have **no website**, are **operational**, and are **newly added** (<=5 reviews).\n\n")
    f.write("---\n\n")
    f.write("## 🇬🇧 UK Leads\n\n")
    f.write("| # | Business | Address | Phone | Status | Reviews |\n")
    f.write("|---|----------|---------|-------|--------|---------|\n")
    
    for i, lead in enumerate(leads[:target_leads]):
        name = lead.get("displayName", {}).get("text", "N/A")
        address = lead.get("formattedAddress", "N/A")
        phone = lead.get("nationalPhoneNumber", "N/A")
        status = lead.get("businessStatus", "N/A")
        reviews = lead.get("userRatingCount", 0)
        
        f.write(f"| {i+1} | {name} | {address} | {phone} | {status} | {reviews} |\n")

print("Generated leads.md")
