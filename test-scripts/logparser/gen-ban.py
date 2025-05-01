#!/usr/bin/env python3

import json
import requests
import time
import os
import re
from collections import Counter
from requests.exceptions import RequestException

def read_banlist(banlist_path):
    """
    Read IP addresses from the banlist.txt file.
    Each line in the file should contain a single IP address.
    """
    ip_addresses = []
    
    try:
        with open(banlist_path, 'r') as file:
            for line in file:
                # Skip empty lines and comments
                if not line.strip() or line.strip().startswith('#'):
                    continue
                
                # Add the IP address to the list
                ip_addresses.append(line.strip())
    except Exception as e:
        print(f"Error reading banlist file: {e}")
    
    return ip_addresses

def get_node_info(ip, timeout=3):
    """
    Make a GET request to the node info endpoint.
    Returns the response if successful, None otherwise.
    """
    url = f"http://{ip}:9000/node/info"
    
    try:
        response = requests.get(url, timeout=timeout)
        return {
            "ip": ip,
            "status_code": response.status_code,
            "response": response.text
        }
    except RequestException as e:
        return {
            "ip": ip,
            "status_code": None,
            "error": str(e)
        }

def main():
    # Define file paths
    banlist_path = "banlist.txt"
    
    # Read IP addresses from banlist.txt
    print(f"Reading IP addresses from: {banlist_path}")
    ip_addresses = read_banlist(banlist_path)
    
    if not ip_addresses:
        print("No IP addresses found in the banlist file.")
        return
    
    print(f"Read {len(ip_addresses)} IP addresses from banlist.")
    
    # Make requests to each IP
    print("\nMaking requests to each IP at port 9000/node/info:")
    print("-" * 80)
    
    results = []
    for i, ip in enumerate(ip_addresses, 1):
        print(f"[{i}/{len(ip_addresses)}] Requesting {ip}:9000/node/info...")
        result = get_node_info(ip)
        results.append(result)
        
        # Print the result
        if "error" in result:
            print(f"  Error: {result['error']}")
        else:
            print(f"  Status: {result['status_code']}")
            print(f"  Response: {result['response'][:100]}..." if len(result['response']) > 100 else f"  Response: {result['response']}")
        
        # Add a small delay to avoid overwhelming the network
        time.sleep(0.1)
    
    # Print summary
    print("\nSummary:")
    print("-" * 80)
    
    # Count different types of responses
    json_responses = 0
    error_responses = 0
    timeout_responses = 0
    
    for result in results:
        if "error" in result:
            if "timed out" in result["error"].lower():
                timeout_responses += 1
            else:
                error_responses += 1
        elif "status_code" in result and result["status_code"] == 200:
            # Check if the response is a valid JSON
            try:
                json.loads(result["response"])
                json_responses += 1
            except json.JSONDecodeError:
                error_responses += 1
        else:
            error_responses += 1
    
    print(f"Total requests: {len(results)}")
    print(f"JSON responses: {json_responses}")
    print(f"Error responses: {error_responses}")
    print(f"Timeout responses: {timeout_responses}")
    
    # Also write the results to a file for later analysis
    with open("banlist_query_results.json", "w") as f:
        json.dump(results, f, indent=2)
    print(f"Results written to banlist_query_results.json")

if __name__ == "__main__":
    main()
