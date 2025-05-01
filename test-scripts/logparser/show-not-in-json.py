#!/usr/bin/env python3

import json
import os
import re
from collections import Counter
from pathlib import Path

def parse_log_file(log_file_path):
    """
    Parse the log file and extract IP addresses from each line.
    The IP address is in the 5th column (c-ip field).
    """
    ip_addresses = []
    
    try:
        with open(log_file_path, 'r') as file:
            for line in file:
                # Skip comment lines that start with #
                if line.startswith('#'):
                    continue
                
                # Split the line by tabs
                parts = line.strip().split('\t')
                
                # Check if the line has enough columns
                if len(parts) >= 5:
                    ip = parts[4]  # c-ip is the 5th column (index 4)
                    ip_addresses.append(ip)
    except Exception as e:
        print(f"Error reading log file: {e}")
    
    return ip_addresses

def load_ips_from_json(json_file_path):
    """
    Load IPs from the JSON file.
    Returns a set of IPs for faster lookup.
    """
    ip_set = set()
    
    try:
        with open(json_file_path, 'r') as file:
            data = json.load(file)
            for item in data:
                if 'ip' in item:
                    ip_set.add(item['ip'])
    except Exception as e:
        print(f"Error loading JSON file: {e}")
    
    return ip_set

def main():
    # Define file paths
    log_file_path = os.path.expanduser("~/logdumps")
    json_file_path = "ips"
    
    # Parse log file to get IP addresses
    print(f"Parsing log file: {log_file_path}")
    ip_addresses = parse_log_file(log_file_path)
    
    if not ip_addresses:
        print("No IP addresses found in the log file.")
        return
    
    # Count occurrences of each IP
    ip_counter = Counter(ip_addresses)
    
    # Load IPs from JSON file
    print(f"Loading IPs from JSON file: {json_file_path}")
    known_ips = load_ips_from_json(json_file_path)
    
    # Sort all IPs by occurrence count (descending)
    sorted_ips = ip_counter.most_common()
    
    # Filter IPs not in the JSON list and write to banlist.txt
    not_in_json_ips = []
    not_in_json_count_sum = 0
    
    for ip, count in sorted_ips:
        in_json = "Yes" if ip in known_ips else "No"
        if in_json == "No":
            not_in_json_ips.append(ip)
            not_in_json_count_sum += count
    
    # Write IPs to banlist.txt
    with open("banlist.txt", 'w') as banfile:
        for ip in not_in_json_ips:
            banfile.write(f"{ip}\n")
    
    print(f"\nWrote {len(not_in_json_ips)} IPs to banlist.txt")
    print(f"Total occurrences of IPs not in JSON list: {not_in_json_count_sum}")
    
    # Calculate statistics
    total_log_ips = len(ip_addresses)
    total_json_ips = len(known_ips)
    
    # Count how many log IPs are in the JSON file
    log_ips_in_json = sum(1 for ip in ip_addresses if ip in known_ips)
    percentage_in_json = (log_ips_in_json / total_log_ips) * 100 if total_log_ips > 0 else 0
    
    # Calculate total count of top 100 IPs
    total_top_100_count = sum(count for _, count in sorted_ips)
    
    # Print summary statistics
    print("\nSummary Statistics:")
    print("-" * 80)
    print(f"Total IPs loaded from JSON file: {total_json_ips}")
    print(f"Total log entries processed: {total_log_ips}")
    print(f"Log IPs present in JSON: {log_ips_in_json} ({percentage_in_json:.2f}%)")
    print(f"Total count of top 100 IPs: {total_top_100_count}")
    
    # Print a sample of JSON IPs (up to 10)
    print("\nSample of IPs from JSON file:")
    print("-" * 80)
    sample_size = min(10, len(known_ips))
    for ip in list(known_ips)[:sample_size]:
        print(ip)

if __name__ == "__main__":
    main()
