#!/usr/bin/env python3

import json
import requests
import time
import os
import re
from collections import Counter
from requests.exceptions import RequestException

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
    log_file_path = os.path.expanduser("~/logdumps")
    
    # Parse log file to get IP addresses
    print(f"Parsing log file: {log_file_path}")
    ip_addresses = parse_log_file(log_file_path)
    
    if not ip_addresses:
        print("No IP addresses found in the log file.")
        return
    
    # Count occurrences of each IP
    ip_counter = Counter(ip_addresses)
    
    # Sort IPs by occurrence count (descending)
    sorted_ips = ip_counter.most_common(100)
    
    # Extract just the IPs from the (ip, count) tuples
    top_ips = [ip for ip, count in sorted_ips]
    print(f"Extracted top {len(top_ips)} IPs by occurrence count.")
    
    # Make requests to each IP
    print("\nMaking requests to each IP at port 9000/node/info:")
    print("-" * 80)
    
    results = []
    for i, ip in enumerate(top_ips, 1):
        print(f"[{i}/{len(top_ips)}] Requesting {ip}:9000/node/info...")
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

if __name__ == "__main__":
    main()
