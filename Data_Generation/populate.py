import argparse
import datetime as dt
import random
import sys
from collections import defaultdict

# --------------------------------------------------------------------------
# Static reference data
# --------------------------------------------------------------------------

# (street, city, state, zip, price multiplier, purchase cost, floors, units/floor)
PROPERTIES = [
    ("412 Brodhead Ave",      "Bethlehem",    "PA", "18015", 1.00, 14_500_000, 3, 6),
    ("1650 Hamilton St",      "Allentown",    "PA", "18102", 0.90, 11_750_000, 3, 5),
    ("1500 Spring Garden St", "Philadelphia", "PA", "19130", 1.45, 41_000_000, 5, 5),
    ("75 River St",           "Hoboken",      "NJ", "07030", 2.05, 62_500_000, 4, 6),
    ("220 Northampton St",    "Easton",       "PA", "18042", 0.95,  9_900_000, 2, 6),
    ("88 W Broad St",         "Bethlehem",    "PA", "18018", 1.05, 18_200_000, 4, 5),
    ("300 Liberty Ave",       "Pittsburgh",   "PA", "15222", 1.15, 23_400_000, 4, 5),
    ("19 Main St",            "Stroudsburg",  "PA", "18360", 0.80,  6_300_000, 2, 5),
]

# (name, category, included?, monthly fee when not included)
COMMON_AMENITIES = [
    ("Fitness center",          "Fitness",     "Y", 0),
    ("Outdoor pool",            "Recreation",  "Y", 0),
    ("Surface parking space",   "Parking",     "N", 75),
    ("Covered garage parking",  "Parking",     "N", 160),
    ("EV charging station",     "Parking",     "N", 60),
    ("On-site laundry room",    "Laundry",     "Y", 0),
    ("Package lockers",         "Convenience", "Y", 0),
    ("Storage locker",          "Storage",     "N", 40),
    ("Bike storage room",       "Storage",     "Y", 0),
    ("Dog park",                "Pet",         "Y", 0),
    ("Rooftop deck",            "Outdoor",     "Y", 0),
    ("Coworking lounge",        "Workspace",   "Y", 0),
]

UNCOMMON_AMENITIES = [
    ("Indoor rock climbing wall", "Recreation", "N", 45),
    ("Infrared sauna",            "Wellness",   "N", 35),
    ("Golf simulator",            "Recreation", "N", 50),
    ("Cold plunge pools",         "Wellness",   "N", 40),
    ("Podcast recording studio",  "Workspace",  "N", 30),
    ("Rooftop beehive garden",    "Outdoor",    "Y", 0),
    ("Dog wash and grooming spa", "Pet",        "Y", 0),
    ("Bike repair workshop",      "Storage",    "Y", 0),
    ("Vinyl listening lounge",    "Recreation", "Y", 0),
    ("Pickleball court",          "Recreation", "Y", 0),
    ("Teaching kitchen",          "Workspace",  "N", 25),
]

PRIVATE_AMENITIES = [
    "In-unit washer/dryer", "Dishwasher", "Balcony", "Walk-in closet", "Hardwood floors",
    "Fireplace", "Central air", "Smart thermostat", "City view", "River view",
    "Private patio", "Kitchen island", "Soaking tub",
]
UNCOMMON_PRIVATE = ["Built-in wine fridge", "Heated bathroom floors",
                    "Private rooftop terrace", "Home theater nook", "Steam shower"]

FIRST_NAMES = """James Mary Robert Patricia John Jennifer Michael Linda David Elizabeth
William Barbara Richard Susan Joseph Jessica Thomas Sarah Charles Karen Daniel Lisa
Matthew Nancy Anthony Betty Mark Sandra Donald Ashley Steven Kimberly Andrew Emily
Kenneth Donna Joshua Michelle Kevin Carol Brian Amanda George Melissa Timothy Deborah
Priya Wei Mohammed Fatima Diego Sofia Hiroshi Yuki Olumide Adaeze Mateo Valentina
Arjun Ananya Minh Linh Omar Layla Kwame Ama Sean Siobhan Luca Giulia Tomasz Agnieszka
Rafael Camila Jae-won Seo-yeon Ibrahim Amina Nikolai Anastasia Kai Leilani""".split()

LAST_NAMES = """Smith Johnson Williams Brown Jones Garcia Miller Davis Rodriguez Martinez
Hernandez Lopez Gonzalez Wilson Anderson Thomas Taylor Moore Jackson Martin Lee Perez
Thompson White Harris Sanchez Clark Ramirez Lewis Robinson Walker Young Allen King
Wright Scott Torres Nguyen Hill Flores Green Adams Nelson Baker Hall Rivera Campbell
Mitchell Carter Roberts Patel Shah Chen Wang Kim Park Tanaka Sato Okafor Adeyemi
Kowalski Nowak Rossi Bianchi O'Brien O'Connor Murphy Kelly Haddad Khalil Ivanov
Petrova Mensah Boateng Silva Santos Kahananui Fischer Weber Schmidt""".split()

PET_NAMES = """Max Bella Luna Charlie Lucy Cooper Daisy Milo Bailey Rocky Sadie Teddy
Nala Oliver Pepper Ziggy Mochi Biscuit Juniper Waffles Pickles Clementine""".split()

AREA_CODES = ["610", "484", "215", "267", "412", "201", "908", "570"]

BANKS = ["Lehigh Valley Federal Credit Union", "Keystone National Bank",
         "Susquehanna Savings", "Delaware River Trust", "Allegheny Community Bank",
         "Hudson Bay Bank", "Pocono Mountain Credit Union"]

PET_TYPES = [  # species, weight, monthly pet fee
    ("Dog", 45, 50), ("Cat", 40, 35), ("Rabbit", 5, 20),
    ("Bird", 5, 20), ("Fish", 3, 0), ("Reptile", 2, 20),
]

RELATIONSHIPS = [("Roommate", 55), ("Spouse", 25), ("Partner", 15), ("Family member", 5)]

# Rough USD price anchors per coin (fictional enterprise, approximate values).
CRYPTO_ANCHORS = {
    "BTC":  [(dt.date(2023, 1, 1), 17_000), (dt.date(2024, 3, 1), 68_000),
             (dt.date(2024, 12, 1), 98_000), (dt.date(2025, 10, 1), 114_000),
             (dt.date(2026, 12, 31), 96_000)],
    "ETH":  [(dt.date(2023, 1, 1), 1_250), (dt.date(2024, 3, 1), 3_600),
             (dt.date(2024, 12, 1), 3_700), (dt.date(2025, 10, 1), 4_100),
             (dt.date(2026, 12, 31), 3_200)],
    "USDC": [(dt.date(2023, 1, 1), 1.0), (dt.date(2026, 12, 31), 1.0)],
}

# --------------------------------------------------------------------------
# Date helpers
# --------------------------------------------------------------------------


def add_months(d, n):
    """Same as Oracle ADD_MONTHS for days <= 28 (all our lease start days)."""
    m = d.month - 1 + n
    return dt.date(d.year + m // 12, m % 12 + 1, d.day)


def lease_end(start, months):
    """Last day covered by a lease: ADD_MONTHS(start, months) - 1."""
    return add_months(start, months) - dt.timedelta(days=1)


def normalize_start(d):
    """Lease start days stay in 1..28 so monthly due dates always exist."""
    if d.day > 28:
        return add_months(d.replace(day=1), 1)
    return d


def rand_date(rng, lo, hi):
    return lo + dt.timedelta(days=rng.randint(0, max(0, (hi - lo).days)))


def crypto_rate(currency, day):
    pts = CRYPTO_ANCHORS[currency]
    for (d0, p0), (d1, p1) in zip(pts, pts[1:]):
        if d0 <= day <= d1:
            frac = (day - d0).days / max(1, (d1 - d0).days)
            return p0 + frac * (p1 - p0)
    return pts[-1][1]


def aba_routing(rng):
    """Random 9-digit routing number with a valid ABA checksum."""
    d = [rng.randint(0, 9) for _ in range(8)]
    s = 3 * (d[0] + d[3] + d[6]) + 7 * (d[1] + d[4] + d[7]) + (d[2] + d[5])
    d.append((10 - s % 10) % 10)
    return "".join(map(str, d))


# --------------------------------------------------------------------------
# Generator
# --------------------------------------------------------------------------


class Generator:
    def __init__(self, seed, today, history_start, n_properties, units_per_property):
        self.rng = random.Random(seed)
        self.today = today
        self.history_start = history_start
        self.n_properties = n_properties
        self.units_per_property = units_per_property
        self.properties, self.apartments, self.amenities, self.apt_amenities = [], [], [], []
        self.tenants, self.visits, self.leases = [], [], []
        self.occupants, self.pets = [], []
        self.payments, self.move_outs = [], []
        self.card, self.ach, self.crypto, self.check = [], [], [], []
        self.crypto_rates = []
        self._ssns, self._emails = set(), set()
        self._tenant_pay = {}

    # ---- people -----------------------------------------------------------

    def new_tenant(self, min_salary=0):
        rng = self.rng
        first, last = rng.choice(FIRST_NAMES), rng.choice(LAST_NAMES)
        while True:
            ssn = f"9{rng.randint(0, 99):02d}-{rng.randint(1, 99):02d}-{rng.randint(1, 9999):04d}"
            if ssn not in self._ssns:
                self._ssns.add(ssn)
                break
        base = f"{first}.{last}".lower().replace("'", "")
        email, n = f"{base}@example.com", 1
        while email in self._emails:
            n += 1
            email = f"{base}{n}@example.com"
        self._emails.add(email)
        salary = max(min_salary * rng.uniform(1.0, 2.6),
                     rng.choice([28_000, 35_000, 42_000, 55_000, 68_000, 85_000]))
        t = {"tenant_id": len(self.tenants) + 1, "name": f"{first} {last}", "ssn": ssn,
             "phone": f"{rng.choice(AREA_CODES)}-555-01{rng.randint(0, 99):02d}",
             "email": email, "salary": float(round(salary, -2)),
             "age": rng.randint(19, 76), "status": "Prospect"}
        self.tenants.append(t)
        return t

    # ---- buildings --------------------------------------------------------

    def build_properties(self):
        rng = self.rng
        for pid, (street, city, state, zipc, mult, cost, floors, per_floor) in enumerate(
                PROPERTIES[:self.n_properties], 1):
            self.properties.append({"property_id": pid, "street": street, "city": city,
                                    "state": state, "zip": zipc, "property_cost": float(cost)})
            common = rng.sample(COMMON_AMENITIES, rng.randint(4, 7))
            uncommon = rng.sample(UNCOMMON_AMENITIES, rng.randint(1, 3))
            if not any(a[2] == "N" for a in common + uncommon):   # at least one paid amenity
                common.append(COMMON_AMENITIES[2])
            for name, cat, inc, fee in common + uncommon:
                self.amenities.append({
                    "amenity_id": len(self.amenities) + 1, "property_id": pid,
                    "amenity_name": name, "category": cat, "is_included": inc,
                    "monthly_fee": 0.0 if inc == "Y" else float(max(5, round(fee * mult / 5) * 5)),
                })
            numbers = [f * 100 + u for f in range(1, floors + 1) for u in range(1, per_floor + 1)]
            if self.units_per_property < len(numbers):
                numbers = sorted(rng.sample(numbers, self.units_per_property))
            for n in numbers:
                apt = self.make_apartment(pid, mult, n)
                self.apartments.append(apt)
                picks = rng.sample(PRIVATE_AMENITIES, rng.randint(2, 5))
                if rng.random() < 0.25:
                    picks.append(rng.choice(UNCOMMON_PRIVATE))
                for name in picks:
                    self.apt_amenities.append({"property_id": pid, "apt_number": apt["apt_number"],
                                               "amenity_name": name})

    def make_apartment(self, pid, mult, number):
        rng = self.rng
        beds = rng.choices([0, 1, 2, 3], weights=[15, 40, 30, 15])[0]
        if beds == 0:
            baths, sqft = 1.0, rng.randint(380, 550)
        elif beds == 1:
            baths, sqft = rng.choice([1.0, 1.0, 1.5]), rng.randint(600, 850)
        elif beds == 2:
            baths, sqft = rng.choice([1.0, 1.5, 2.0, 2.0]), rng.randint(850, 1200)
        else:
            baths, sqft = rng.choice([2.0, 2.5, 2.5]), rng.randint(1150, 1600)
        rent = round((650 + 0.95 * sqft + 140 * beds + 150 * (baths - 1)) * mult / 5) * 5
        deposit = rent if rng.random() < 0.6 else round(rent * 1.5 / 5) * 5
        return {"property_id": pid, "apt_number": str(number), "rent": float(rent),
                "security_deposit": float(deposit), "square_feet": float(sqft),
                "bedroom_amount": beds, "bathroom_amount": baths}

    # ---- occupancy history -------------------------------------------------

    def historical_rate(self, apt, start):
        """Rent was ~3.5%/yr lower in the past."""
        years_back = max(0.0, (self.today - start).days / 365.25)
        return round(apt["rent"] / (1.035 ** years_back) / 5) * 5

    def simulate_apartment(self, apt):
        rng = self.rng
        cursor = normalize_start(rand_date(rng, self.history_start,
                                           self.history_start + dt.timedelta(days=300)))
        if rng.random() < 0.6:
            cursor = cursor.replace(day=1)
        while cursor <= self.today:
            rate = self.historical_rate(apt, cursor)
            tenant = self.new_tenant(min_salary=40 * rate)
            self.record_visits_before(tenant, apt, cursor)
            # household and extras stay the same across renewals
            other = None
            if apt["bedroom_amount"] >= 1 and rng.random() < 0.35:
                other = (self.new_tenant(), rng.choices([r for r, _ in RELATIONSHIPS],
                                                        weights=[w for _, w in RELATIONSHIPS])[0])
            pets = []
            if rng.random() < 0.30:
                for _ in range(1 if rng.random() < 0.8 else 2):
                    species, _, fee = rng.choices(PET_TYPES, weights=[p[1] for p in PET_TYPES])[0]
                    pets.append((rng.choice(PET_NAMES), species, float(fee)))
            start = cursor
            while True:
                months = rng.choices([12, 6, 9, 18, 24], weights=[50, 22, 14, 9, 5])[0]
                lease = self.add_lease(tenant, apt, start, months, rate, other, pets)
                end = lease["end"]
                if end >= self.today:          # lease is current
                    self.maybe_mid_lease_pet(lease)
                    self.maybe_future_move_out(lease)
                    return
                if rng.random() < 0.45:        # renew: next lease starts the next day
                    start = end + dt.timedelta(days=1)
                    rate = round(rate * rng.uniform(1.02, 1.045) / 5) * 5
                    continue
                self.add_move_out(lease, apt)
                break
            if rng.random() < 0.20:            # unit sits empty to the present
                return
            cursor = normalize_start(end + dt.timedelta(days=rng.randint(4, 80)))

    def add_lease(self, tenant, apt, start, months, rate, other, pets):
        lease = {"lease_id": len(self.leases) + 1, "tenant_id": tenant["tenant_id"],
                 "property_id": apt["property_id"], "apt_number": apt["apt_number"],
                 "start_date": start, "number_months": months, "rate": float(rate),
                 "end": lease_end(start, months), "pets": [],
                 "people": [tenant["tenant_id"]]}
        self.leases.append(lease)
        if other:
            person, rel = other
            self.occupants.append({"lease_id": lease["lease_id"], "tenant_id": person["tenant_id"],
                                   "relationship": rel, "added_date": start})
            lease["people"].append(person["tenant_id"])
        for name, species, fee in pets:
            self.add_pet(lease, name, species, fee, start)
        return lease

    def add_pet(self, lease, name, species, fee, added):
        pet = {"pet_id": len(self.pets) + 1, "lease_id": lease["lease_id"], "pet_name": name,
               "species": species, "monthly_fee": fee, "added_date": added}
        self.pets.append(pet)
        lease["pets"].append(pet)

    def maybe_mid_lease_pet(self, lease):
        """A few current tenants adopted a pet partway through their lease."""
        start = lease["start_date"]
        if start < self.today - dt.timedelta(days=45) and self.rng.random() < 0.15:
            added = rand_date(self.rng, start + dt.timedelta(days=20), self.today - dt.timedelta(days=1))
            species, _, fee = self.rng.choices(PET_TYPES[:2], weights=[1, 1])[0]
            self.add_pet(lease, self.rng.choice(PET_NAMES), species, float(fee), added)

    def record_visits_before(self, tenant, apt, start):
        rng = self.rng
        when = start - dt.timedelta(days=rng.randint(3, 35))
        self.add_visit(tenant, apt["property_id"], apt["apt_number"], when)
        if rng.random() < 0.4:   # also toured another unit in the same building
            others = [a for a in self.apartments
                      if a["property_id"] == apt["property_id"] and a is not apt]
            if others:
                o = rng.choice(others)
                self.add_visit(tenant, o["property_id"], o["apt_number"],
                               when - dt.timedelta(days=rng.randint(0, 6)))

    def add_visit(self, tenant, pid, apt_no, day):
        rng = self.rng
        ts = dt.datetime.combine(day, dt.time(rng.randint(9, 17), rng.choice([0, 15, 30, 45])))
        self.visits.append({"visit_id": len(self.visits) + 1, "tenant_id": tenant["tenant_id"],
                            "property_id": pid, "apt_number": apt_no, "visit_time": ts})

    def add_move_out(self, lease, apt):
        rng = self.rng
        dep = apt["security_deposit"]
        roll = rng.random()
        reason = None
        if roll < 0.60:
            refund = dep
        elif roll < 0.90:
            refund = max(0.0, dep - round(dep * rng.uniform(0.1, 0.6) / 5) * 5)
            reason = rng.choice(["Carpet cleaning and wall repair", "Damaged blinds and cabinet door",
                                 "Professional cleaning required", "Pet damage to flooring",
                                 "Unpaid utility charges"])
        else:
            refund = 0.0
            reason = rng.choice(["Extensive damage throughout the unit",
                                 "Unpaid rent applied against deposit"])
        leave = lease["end"] - dt.timedelta(days=rng.choice([0, 0, 0, 1, 2, 5]))
        self.move_outs.append({"lease_id": lease["lease_id"], "leave_date": leave,
                               "refund_amount": float(refund), "deduction_reason": reason})

    def maybe_future_move_out(self, lease):
        """Current lease ending soon whose tenant already gave notice:
        move-out date set, refund not decided yet (NULL)."""
        if (lease["end"] - self.today).days <= 75 and self.rng.random() < 0.5:
            self.move_outs.append({"lease_id": lease["lease_id"], "leave_date": lease["end"],
                                   "refund_amount": None, "deduction_reason": None})

    # ---- prospects ----------------------------------------------------------

    def build_prospects(self, count):
        rng = self.rng
        occupied = {(l["property_id"], l["apt_number"]) for l in self.leases
                    if l["start_date"] <= self.today <= l["end"]}
        vacant = [a for a in self.apartments
                  if (a["property_id"], a["apt_number"]) not in occupied]
        for _ in range(count):
            t = self.new_tenant()
            for _ in range(rng.randint(1, 3)):
                pool = vacant if vacant and rng.random() < 0.7 else self.apartments
                a = rng.choice(pool)
                day = self.today - dt.timedelta(days=rng.randint(1, 120))
                self.add_visit(t, a["property_id"], a["apt_number"], day)

    # ---- billing (mirrors the PL/SQL functions) ------------------------------

    @staticmethod
    def charge_on(lease, due):
        return lease["rate"] + sum(
            p["monthly_fee"] for p in lease["pets"] if p["added_date"] <= due)

    def due_dates(self, lease, until):
        return [d for d in (add_months(lease["start_date"], i) for i in range(lease["number_months"]))
                if d <= until]

    # ---- payments -------------------------------------------------------------

    def payment_profile(self, tenant_id, first_day):
        """Each tenant tends to pay the same way every month."""
        if tenant_id in self._tenant_pay:
            return self._tenant_pay[tenant_id]
        rng = self.rng
        prof = {"method": rng.choices(["CARD", "ACH", "CHECK", "CRYPTO"], weights=[48, 30, 13, 9])[0],
                "habit": rng.choices(["prompt", "late", "delinquent"], weights=[62, 23, 15])[0],
                "card": self.new_card(first_day),
                "ach": {"bank_name": rng.choice(BANKS), "routing_number": aba_routing(rng),
                        "account_last_four": f"{rng.randint(0, 9999):04d}",
                        "account_type": rng.choice(["CHECKING", "CHECKING", "SAVINGS"])},
                "crypto": {"currency": rng.choices(["BTC", "ETH", "USDC"], weights=[40, 30, 30])[0],
                           "wallet_address": "0x" + "".join(rng.choice("0123456789abcdef")
                                                            for _ in range(40))},
                "check_bank": rng.choice(BANKS),
                "next_check": rng.randint(1001, 4800)}
        self._tenant_pay[tenant_id] = prof
        return prof

    def new_card(self, from_day):
        rng = self.rng
        exp = add_months(from_day.replace(day=1), rng.randint(18, 60))
        return {"card_type": rng.choice(["CREDIT", "DEBIT"]),
                "card_brand": rng.choices(["Visa", "Mastercard", "Amex", "Discover"],
                                          weights=[50, 30, 12, 8])[0],
                "last_four": f"{rng.randint(0, 9999):04d}",
                "exp_month": exp.month, "exp_year": exp.year}

    def build_payments(self):
        rng = self.rng
        for lease in self.leases:
            prof = self.payment_profile(lease["tenant_id"], lease["start_date"])
            dues = self.due_dates(lease, self.today)
            current = lease["end"] >= self.today
            skip = set()
            if prof["habit"] == "delinquent" and current and len(dues) > 1:
                skip = set(dues[-rng.randint(1, 3):])        # behind on rent now
            elif prof["habit"] == "delinquent" and not current and rng.random() < 0.4:
                skip = {dues[-1]}                             # skipped the final month
            for due in dues:
                if due in skip:
                    continue
                if prof["habit"] == "prompt":
                    paid = due + dt.timedelta(days=rng.randint(-4, 1))
                else:
                    paid = due + dt.timedelta(days=rng.choice([-2, 0, 1, 3, 6, 9, 14, 20]))
                paid = max(paid, lease["start_date"])
                if paid > self.today:
                    continue                                  # not paid yet
                payer = lease["people"][0]
                if len(lease["people"]) > 1 and rng.random() < 0.3:
                    payer = lease["people"][1]
                amount = round(self.charge_on(lease, due), 2)
                if rng.random() < 0.04:                       # split into two payments
                    first = round(amount * 0.6, 2)
                    second_day = paid + dt.timedelta(days=rng.randint(5, 12))
                    self.add_payment(lease, prof, payer, first, paid)
                    if second_day <= self.today:
                        self.add_payment(lease, prof, payer, round(amount - first, 2), second_day)
                else:
                    self.add_payment(lease, prof, payer, amount, paid)

    def add_payment(self, lease, prof, payer, amount, day):
        rng = self.rng
        method = prof["method"]
        if rng.random() < 0.08:  # occasionally pays a different way
            method = rng.choice(["CARD", "ACH", "CHECK", "CRYPTO"])
        pid = len(self.payments) + 1
        self.payments.append({"payment_id": pid, "lease_id": lease["lease_id"], "paid_by": payer,
                              "amount": amount, "payment_date": day, "payment_method": method})
        if method == "CARD":
            c = prof["card"]
            if (c["exp_year"], c["exp_month"]) < (day.year, day.month):
                c = prof["card"] = self.new_card(day)          # card was reissued
            fee = round(amount * 0.03, 2) if c["card_type"] == "CREDIT" else 0.0
            self.card.append({"payment_id": pid, **c, "processing_fee": fee})
        elif method == "ACH":
            self.ach.append({"payment_id": pid, **prof["ach"]})
        elif method == "CHECK":
            self.check.append({"payment_id": pid, "check_number": prof["next_check"],
                               "bank_name": prof["check_bank"]})
            prof["next_check"] += 1
        else:
            cur = prof["crypto"]["currency"]
            rate = 1.0 if cur == "USDC" else round(crypto_rate(cur, day) * rng.uniform(0.97, 1.03), 4)
            self.crypto.append({"payment_id": pid, "currency": cur,
                                "wallet_address": prof["crypto"]["wallet_address"],
                                "transaction_hash": "0x" + "%064x" % rng.getrandbits(256),
                                "crypto_amount": round(amount / rate, 8), "exchange_rate": rate})

    # ---- statuses and rates ---------------------------------------------------

    def set_statuses(self):
        has_lease, current = set(), set()
        for l in self.leases:
            on_lease = set(l["people"])
            has_lease |= on_lease
            if l["end"] >= self.today:
                current |= on_lease
        for t in self.tenants:
            tid = t["tenant_id"]
            t["status"] = "Active" if tid in current else "Former" if tid in has_lease else "Prospect"

    def build_crypto_rates(self):
        for cur in ("BTC", "ETH", "USDC"):
            rate = 1.0 if cur == "USDC" else round(crypto_rate(cur, self.today), 4)
            self.crypto_rates.append({"currency": cur, "usd_per_coin": rate,
                                      "updated_on": self.today})

    def run(self, prospects):
        self.build_properties()
        for apt in self.apartments:
            self.simulate_apartment(apt)
        self.build_prospects(prospects)
        self.build_payments()
        self.set_statuses()
        self.build_crypto_rates()
        self.validate()

    # ---- self-check ---------------------------------------------------------------

    def validate(self):
        tids = {t["tenant_id"] for t in self.tenants}
        apts = {(a["property_id"], a["apt_number"]) for a in self.apartments}
        leases = {l["lease_id"]: l for l in self.leases}
        pays = {p["payment_id"]: p for p in self.payments}
        for v in self.visits:
            assert v["tenant_id"] in tids and (v["property_id"], v["apt_number"]) in apts
            assert v["visit_time"].date() <= self.today
        by_apt = defaultdict(list)
        for l in self.leases:
            assert l["tenant_id"] in tids and (l["property_id"], l["apt_number"]) in apts
            assert l["start_date"] <= self.today and l["start_date"].day <= 28
            by_apt[(l["property_id"], l["apt_number"])].append(l)
        for ls in by_apt.values():                       # no double-booked units
            ls.sort(key=lambda l: l["start_date"])
            for a, b in zip(ls, ls[1:]):
                assert a["end"] < b["start_date"], (a, b)
        visited = {(v["tenant_id"], v["property_id"], v["apt_number"]): v["visit_time"]
                   for v in self.visits}
        for l in self.leases:                            # visited before signing
            assert (l["tenant_id"], l["property_id"], l["apt_number"]) in visited
        seen_occ = set()
        for o in self.occupants:
            assert o["lease_id"] in leases and o["tenant_id"] in tids
            assert o["tenant_id"] != leases[o["lease_id"]]["tenant_id"]
            assert (o["lease_id"], o["tenant_id"]) not in seen_occ
            seen_occ.add((o["lease_id"], o["tenant_id"]))
        for a in self.amenities:
            assert (a["is_included"] == "Y") == (a["monthly_fee"] == 0)
        mo = [m["lease_id"] for m in self.move_outs]
        assert len(mo) == len(set(mo))
        for m in self.move_outs:
            l = leases[m["lease_id"]]
            assert l["start_date"] <= m["leave_date"] <= l["end"]
            if m["refund_amount"] is not None:
                assert m["leave_date"] <= self.today
        paid = defaultdict(float)
        for p in self.payments:
            l = leases[p["lease_id"]]
            assert p["amount"] > 0 and l["start_date"] <= p["payment_date"] <= self.today
            assert p["paid_by"] in l["people"]
            paid[p["lease_id"]] += p["amount"]
        for lid, total in paid.items():                  # never overpaid
            l = leases[lid]
            obligation = sum(self.charge_on(l, d) for d in self.due_dates(l, l["end"]))
            assert total <= obligation + 0.01, (lid, total, obligation)
        subs = {"CARD": self.card, "ACH": self.ach, "CRYPTO": self.crypto, "CHECK": self.check}
        seen = set()
        for method, rows in subs.items():
            for r in rows:
                assert pays[r["payment_id"]]["payment_method"] == method
                assert r["payment_id"] not in seen
                seen.add(r["payment_id"])
        assert seen == set(pays)                          # disjoint and total
        for c in self.card:
            p = pays[c["payment_id"]]["payment_date"]
            assert (c["exp_year"], c["exp_month"]) >= (p.year, p.month)
        hashes = [c["transaction_hash"] for c in self.crypto]
        assert len(hashes) == len(set(hashes))


# --------------------------------------------------------------------------
# SQL output
# --------------------------------------------------------------------------


def sql_val(v):
    if v is None:
        return "NULL"
    if isinstance(v, dt.datetime):
        return f"TIMESTAMP '{v:%Y-%m-%d %H:%M:%S}'"
    if isinstance(v, dt.date):
        return f"DATE '{v:%Y-%m-%d}'"
    if isinstance(v, float):
        if v == int(v):
            return str(int(v))
        return f"{v:.8f}".rstrip("0").rstrip(".")
    if isinstance(v, int):
        return str(v)
    return "'" + str(v).replace("'", "''") + "'"


def insert_block(out, table, cols, rows, batch=100):
    """INSERT ALL in batches: far fewer statements than one INSERT per row."""
    if not rows:
        return
    out.write(f"\n-- {table}: {len(rows)} rows\n")
    for i in range(0, len(rows), batch):
        out.write("INSERT ALL\n")
        for r in rows[i:i + batch]:
            vals = ", ".join(sql_val(r[c]) for c in cols)
            out.write(f"  INTO {table} ({', '.join(cols)}) VALUES ({vals})\n")
        out.write("SELECT 1 FROM dual;\n")


TABLES = [  # (table, columns, attribute of the generator)
    ("Properties", ["property_id", "street", "city", "state", "zip", "property_cost"], "properties"),
    ("Amenities", ["amenity_id", "property_id", "amenity_name", "category", "is_included",
                   "monthly_fee"], "amenities"),
    ("Apartments", ["property_id", "apt_number", "rent", "security_deposit", "square_feet",
                    "bedroom_amount", "bathroom_amount"], "apartments"),
    ("Apartment_Amenities", ["property_id", "apt_number", "amenity_name"], "apt_amenities"),
    ("Tenants", ["tenant_id", "name", "ssn", "phone", "email", "salary", "age", "status"], "tenants"),
    ("Visits", ["visit_id", "tenant_id", "property_id", "apt_number", "visit_time"], "visits"),
    ("Leases", ["lease_id", "tenant_id", "property_id", "apt_number", "start_date",
                "number_months", "rate"], "leases"),
    ("Lease_Occupants", ["lease_id", "tenant_id", "relationship", "added_date"], "occupants"),
    ("Pets", ["pet_id", "lease_id", "pet_name", "species", "monthly_fee", "added_date"], "pets"),
    ("Crypto_Rates", ["currency", "usd_per_coin", "updated_on"], "crypto_rates"),
    ("Payments", ["payment_id", "lease_id", "paid_by", "amount", "payment_date",
                  "payment_method"], "payments"),
    ("Card_Payments", ["payment_id", "card_type", "card_brand", "last_four", "exp_month",
                       "exp_year", "processing_fee"], "card"),
    ("ACH_Payments", ["payment_id", "bank_name", "routing_number", "account_last_four",
                      "account_type"], "ach"),
    ("Crypto_Payments", ["payment_id", "currency", "wallet_address", "transaction_hash",
                         "crypto_amount", "exchange_rate"], "crypto"),
    ("Check_Payments", ["payment_id", "check_number", "bank_name"], "check"),
    ("Move_Outs", ["lease_id", "leave_date", "refund_amount", "deduction_reason"], "move_outs"),
]

IDENTITY_COLUMNS = [("Properties", "property_id"), ("Amenities", "amenity_id"),
                    ("Tenants", "tenant_id"), ("Visits", "visit_id"), ("Leases", "lease_id"),
                    ("Pets", "pet_id"), ("Payments", "payment_id")]


def write_sql(g, path):
    with open(path, "w", encoding="utf-8") as out:
        out.write(f"-- LU Uncommons test data, generated by datagen/generate_data.py\n"
                  f"-- Reference date (\"today\" in the data): {g.today}\n"
                  f"-- Run after schema.sql, as a script\n"
                  f"-- (F5 in SQL Developer, or @populate.sql in SQL*Plus).\n"
                  f"-- Safe to re-run: it clears every table first.\n\n"
                  "SET DEFINE OFF\nSET FEEDBACK OFF\n\n"
                  "-- Leases and payments are normally locked; unlock them while reloading.\n"
                  "ALTER TRIGGER trg_lease_locked DISABLE;\n"
                  "ALTER TRIGGER trg_payment_locked DISABLE;\n\n"
                  "-- Clear old data, children before parents\n")
        for table, _, _ in reversed(TABLES):
            out.write(f"DELETE FROM {table};\n")
        for table, cols, attr in TABLES:
            insert_block(out, table, cols, getattr(g, attr))
        out.write("\nCOMMIT;\n\n"
                  "ALTER TRIGGER trg_lease_locked ENABLE;\n"
                  "ALTER TRIGGER trg_payment_locked ENABLE;\n\n"
                  "-- Move each identity column past the IDs inserted above, so rows the\n"
                  "-- application inserts later get fresh, non-colliding IDs.\n")
        for table, col in IDENTITY_COLUMNS:
            out.write(f"ALTER TABLE {table} MODIFY {col} "
                      f"GENERATED BY DEFAULT AS IDENTITY (START WITH LIMIT VALUE);\n")
        out.write('\n-- Changing the identity columns above marks the views and triggers INVALID;\n-- Oracle would recompile them on first use, but compile them now so they show VALID.\nALTER VIEW Lease_Details COMPILE;\nALTER VIEW Current_Leases COMPILE;\nALTER VIEW Lease_Balances COMPILE;\nALTER TRIGGER trg_lease_locked COMPILE;\nALTER TRIGGER trg_payment_locked COMPILE;\nALTER TRIGGER trg_lease_needs_visit COMPILE;\n')
        out.write("\nSET FEEDBACK ON\n")


def write_notes(g, path):
    """Summary plus good IDs to try, for the README."""
    today = g.today
    leases = {l["lease_id"]: l for l in g.leases}
    moved = {m["lease_id"]: m for m in g.move_outs}
    current = [l for l in g.leases if l["start_date"] <= today <= l["end"]]
    occupied = {(l["property_id"], l["apt_number"]) for l in current}
    vacant = [a for a in g.apartments if (a["property_id"], a["apt_number"]) not in occupied]
    paid = defaultdict(float)
    for p in g.payments:
        paid[p["lease_id"]] += p["amount"]
    owing = []
    for l in current:
        billed = sum(g.charge_on(l, d) for d in g.due_dates(l, today))
        bal = round(billed - paid[l["lease_id"]], 2)
        if bal > 0.005:
            owing.append((bal, l))
    owing.sort(key=lambda x: -x[0])
    lease_count = defaultdict(int)
    for l in g.leases:
        lease_count[l["tenant_id"]] += 1
    renewed = [tid for tid, n in lease_count.items() if n >= 2]
    with_pets = sorted({leases[p["lease_id"]]["tenant_id"] for p in g.pets
                        if leases[p["lease_id"]]["end"] >= today})
    roommates = [(o["tenant_id"], o["lease_id"]) for o in g.occupants
                 if leases[o["lease_id"]]["end"] >= today]
    prospects = [t["tenant_id"] for t in g.tenants if t["status"] == "Prospect"]
    pending = [m["lease_id"] for m in g.move_outs if m["refund_amount"] is None]
    status_count = defaultdict(int)
    for t in g.tenants:
        status_count[t["status"]] += 1
    revenue = defaultdict(float)
    for p in g.payments:
        revenue[p["payment_date"].year] += p["amount"]
    names = {t["tenant_id"]: t["name"] for t in g.tenants}

    with open(path, "w", encoding="utf-8") as f:
        f.write(f"LU Uncommons test data summary (reference date {today})\n")
        f.write("=" * 64 + "\n\n")
        for table, _, attr in TABLES:
            f.write(f"  {table:<22}{len(getattr(g, attr)):>6}\n")
        f.write("\nPeople by status: " + ", ".join(
            f"{k} {v}" for k, v in sorted(status_count.items())) + "\n")
        f.write(f"Occupied units now: {len(occupied)} of {len(g.apartments)} "
                f"({len(vacant)} vacant)\n")
        f.write("Rent collected by year: " + ", ".join(
            f"{y} ${v:,.0f}" for y, v in sorted(revenue.items())) + "\n\n")

        f.write("Good IDs to test with\n" + "-" * 64 + "\n")
        f.write("Tenants who currently owe money (tenant ID, name: amount due, property-apt):\n")
        for bal, l in owing[:8]:
            f.write(f"  {l['tenant_id']:>4}  {names[l['tenant_id']]}: ${bal:,.2f}  "
                    f"{l['property_id']}-{l['apt_number']}\n")
        f.write("Tenants on a current lease with pets: " + ", ".join(map(str, with_pets[:10])) + "\n")
        f.write("Roommates/spouses on current leases (tenant ID on lease ID): " + ", ".join(
            f"{t} on {l}" for t, l in roommates[:8]) + "\n")
        f.write("Tenants who renewed at least once: " + ", ".join(map(str, renewed[:12])) + "\n")
        f.write("Prospects (visited, never leased): " + ", ".join(map(str, prospects[:12])) + "\n")
        f.write("Leases with a move-out scheduled but not completed: "
                + (", ".join(map(str, pending)) or "none") + "\n")
        f.write("Vacant units right now (property-apt): " + ", ".join(
            f"{a['property_id']}-{a['apt_number']}" for a in vacant[:15]) + "\n")


def main():
    ap = argparse.ArgumentParser(description="Generate LU Uncommons test data.")
    ap.add_argument("-o", "--output", default="populate.sql")
    ap.add_argument("--notes", default="data_notes.txt")
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--today", default=dt.date.today().isoformat(),
                    help="reference date for 'now' in the data (YYYY-MM-DD), default today")
    ap.add_argument("--prospects", type=int, default=15)
    ap.add_argument("--properties", type=int, default=5,
                    help=f"number of properties (1-{len(PROPERTIES)})")
    ap.add_argument("--units", type=int, default=5,
                    help="apartments per property (up to the building's size)")
    ap.add_argument("--history-start", default="2024-11-01",
                    help="earliest lease start; earlier means more history")
    args = ap.parse_args()
    try:
        today = dt.date.fromisoformat(args.today)
        history_start = dt.date.fromisoformat(args.history_start)
    except ValueError:
        sys.exit("--today and --history-start must look like 2026-11-07")
    if history_start >= today:
        sys.exit("--history-start must be before --today")
    if not 1 <= args.properties <= len(PROPERTIES) or args.units < 1 or args.prospects < 0:
        sys.exit(f"--properties must be 1-{len(PROPERTIES)}, --units at least 1, "
                 "--prospects not negative")
    g = Generator(args.seed, today, history_start, args.properties, args.units)
    g.run(args.prospects)
    write_sql(g, args.output)
    write_notes(g, args.notes)
    print(f"Wrote {args.output} and {args.notes}")


if __name__ == "__main__":
    main()